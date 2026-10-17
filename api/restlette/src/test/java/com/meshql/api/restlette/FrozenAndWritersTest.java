package com.meshql.api.restlette;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Plugin;
import com.meshql.core.config.RestletteConfig;
import com.meshql.core.config.StorageConfig;
import com.meshql.repositories.memory.InMemoryPlugin;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code frozen} (an event: create-only) and {@code writers} (a projection: one
 * party writes) on a restlette, over HTTP.
 */
class FrozenAndWritersTest {
    private static final int PORT = 4571;
    private static final String BASE = "http://localhost:" + PORT;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static Server server;
    private static Map<String, Plugin> plugins;

    private static RestletteConfig config(String path, boolean frozen, List<String> writers) throws Exception {
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\"}");
        return RestletteConfig.builder().path(path).port(PORT).storage(new StorageConfig("memory"))
                .schema(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema))
                .frozen(frozen).writers(writers).build();
    }

    @BeforeAll
    static void start() throws Exception {
        plugins = Map.of("memory", new InMemoryPlugin());
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(PORT);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        for (var rc : List.of(config("/event/api", true, List.of()),
                config("/projection/api", false, List.of("the-worker")),
                config("/actor/api", false, List.of()))) {
            context.addServlet(new ServletHolder(new Restlette(rc, plugins, new NoAuth(),
                    new JSONSchemaValidator(MAPPER.convertValue(rc.schema().getSchemaNode(), Map.class)))), rc.path() + "/*");
        }
        server.start();
    }

    @AfterAll
    static void stop() throws Exception {
        server.stop();
    }

    private static String jwt(String sub) {
        var enc = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + enc.encodeToString("{\"alg\":\"none\"}".getBytes()) + "."
                + enc.encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes()) + ".x";
    }

    private static HttpResponse<String> call(String method, String url, String auth, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A restlette answers a create with the payload, not the id: find the id in storage by the payload's tag. */
    private static String idOf(String tag) {
        return plugins.get("memory").createRepository(new StorageConfig("memory")).list(new NoAuth().systemSession()).stream()
                .filter(e -> tag.equals(e.payload().get("tag"))).findFirst().orElseThrow().id();
    }

    private static String body(String tag, int n) {
        return "{\"tag\":\"" + tag + "\",\"n\":" + n + "}";
    }

    @Test
    void aFrozenEntityIsCreatedAndReadButNeverReplacedOrRemoved() throws Exception {
        var created = call("POST", BASE + "/event/api", null, body("e1", 1));
        assertEquals(201, created.statusCode());
        String url = BASE + "/event/api/" + idOf("e1");
        assertEquals(200, call("GET", url, null, null).statusCode());
        assertEquals(405, call("PUT", url, null, body("e1", 2)).statusCode());
        assertEquals(405, call("DELETE", url, null, null).statusCode());
        assertEquals(1, MAPPER.readTree(call("GET", url, null, null).body()).get("n").asInt());
    }

    @Test
    void anOpenEntityCanStillBeReplaced() throws Exception {
        call("POST", BASE + "/actor/api", null, body("a1", 1));
        String url = BASE + "/actor/api/" + idOf("a1");
        assertEquals(200, call("PUT", url, null, body("a1", 2)).statusCode());
        assertEquals(200, call("DELETE", url, null, null).statusCode());
    }

    @Test
    void onlyANamedWriterWritesAndEveryoneReads() throws Exception {
        String url = BASE + "/projection/api";
        assertEquals(403, call("POST", url, null, body("x", 1)).statusCode());
        assertEquals(403, call("POST", url, jwt("front-end"), body("x", 1)).statusCode());
        assertEquals(403, call("POST", url, "Bearer the-wolf", body("x", 1)).statusCode());

        var created = call("POST", url, jwt("the-worker"), body("p1", 1));
        assertEquals(201, created.statusCode());
        String doc = url + "/" + idOf("p1");

        assertEquals(403, call("PUT", doc, jwt("front-end"), body("p1", 9)).statusCode());
        assertEquals(403, call("DELETE", doc, null, null).statusCode());
        assertEquals(200, call("PUT", doc, jwt("the-worker"), body("p1", 2)).statusCode());
        assertEquals(201, call("POST", url, "Bearer the-worker", body("p2", 3)).statusCode());
        assertEquals(200, call("GET", doc, null, null).statusCode());
        assertEquals(2, MAPPER.readTree(call("GET", doc, null, null).body()).get("n").asInt());
    }
}
