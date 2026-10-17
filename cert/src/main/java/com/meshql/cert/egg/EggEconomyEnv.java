package com.meshql.cert.egg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.core.Config;
import com.meshql.core.Plugin;
import com.meshql.core.config.GraphletteConfig;
import com.meshql.core.config.RestletteConfig;
import com.meshql.core.config.RootConfig;
import com.meshql.core.config.StorageConfig;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The deployment of the egg economy: fourteen entities, each with a restlette at
 * {@code /<entity>/api} and a graphlette at {@code /<entity>/graph}, on one server.
 *
 * <p>What makes it the egg economy rather than fourteen entities is how the
 * restlettes are declared:
 * <ul>
 *   <li>an event is {@code frozen}: create-only, PUT and DELETE answer 405;</li>
 *   <li>a projection names its {@code writers}: only the worker's credential writes,
 *       the front end gets 403;</li>
 *   <li>an actor is an ordinary restlette.</li>
 * </ul>
 * Nothing here knows how an event reaches a worker. That is {@link EventBridge}, which
 * belongs to the harness, not to the deployment.
 */
public class EggEconomyEnv {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);

    /** The credential the front end presents. It is in no restlette's {@code writers}. */
    public static final String FRONT_END = "front-end";
    /** The credential the workers present. It is the one {@code writers} of every projection. */
    public static final String WORKER = "egg-worker";

    private final int port;
    private final EggBackend backend;
    private final Path schemas;

    public EggEconomyEnv(int port, EggBackend backend) throws IOException {
        this.port = port;
        this.backend = backend;
        this.schemas = Files.createTempDirectory("egg_economy_graph_");
        this.schemas.toFile().deleteOnExit();
        for (EggEconomy.Entity e : EggEconomy.ENTITIES) {
            Path file = schemas.resolve(e.name() + ".graphql");
            Files.write(file, resource("graph/" + e.name() + ".graphql"));
            file.toFile().deleteOnExit();
        }
    }

    /** An unsigned JWT whose subject is {@code sub}: the edge's job is to authenticate; the deployment reads the subject. */
    public static String bearer(String sub) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + enc.encodeToString("{\"alg\":\"none\"}".getBytes())
                + "." + enc.encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes()) + ".unsigned";
    }

    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = EggEconomyEnv.class.getResourceAsStream("/egg-economy/" + name)) {
            if (in == null) {
                throw new IOException("missing classpath resource egg-economy/" + name);
            }
            return in.readAllBytes();
        }
    }

    private static JsonSchema jsonSchema(String entity) throws IOException {
        JsonNode node = MAPPER.readTree(resource("json/" + entity + ".schema.json"));
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(node);
    }

    public Config buildConfig() throws IOException {
        Config.Builder config = Config.builder().port(port);
        for (EggEconomy.Entity e : EggEconomy.ENTITIES) {
            config.graphlette(graphlette(e));
            config.restlette(restlette(e));
        }
        return config.build();
    }

    private GraphletteConfig.Builder graphlette(EggEconomy.Entity e) {
        EggQueries q = backend.queries();
        RootConfig.Builder root = RootConfig.builder()
                .singleton("getById", q.byId())
                .vector("getAll", q.all());
        if (e.kind() == EggEconomy.Kind.ACTOR) {
            root.vector("getByName", q.byField("name", "name"));
        }
        for (EggEconomy.Ref ref : EggEconomy.REFS) {
            if (ref.from().equals(e.name())) {
                // the entity refers to ref.to(): find by the reference, and follow it
                root.vector(ref.queryName(), q.byField(ref.fk(), "id"));
                root.singletonResolver(ref.forward(), ref.fk(), "getById", graphUrl(ref.to()));
            }
            if (ref.to().equals(e.name())) {
                // ref.from() refers to the entity: list the documents that do
                root.vectorResolver(ref.backward(), null, ref.queryName(), graphUrl(ref.from()));
            }
        }
        return GraphletteConfig.builder()
                .path("/" + e.name() + "/graph")
                .storage(backend.storageFor().apply(e.name()))
                .schema(schemas.resolve(e.name() + ".graphql").toString())
                .rootConfig(root);
    }

    private RestletteConfig.Builder restlette(EggEconomy.Entity e) throws IOException {
        return RestletteConfig.builder()
                .path("/" + e.name() + "/api")
                .port(port)
                .storage(backend.storageFor().apply(e.name()))
                .schema(jsonSchema(e.name()))
                .frozen(e.kind() == EggEconomy.Kind.EVENT)
                .writers(e.kind() == EggEconomy.Kind.PROJECTION ? List.of(WORKER) : List.of());
    }

    public String platformUrl() {
        return "http://localhost:" + port;
    }

    public String graphUrl(String entity) {
        return platformUrl() + "/" + entity + "/graph";
    }

    public String apiUrl(String entity) {
        return platformUrl() + "/" + entity + "/api";
    }

    public int port() {
        return port;
    }

    /**
     * Start the server. Reflection keeps this module free of a compile-time dependency
     * on the server module, as the rest of the certification harness does.
     */
    public Object start(Map<String, Plugin> plugins) throws Exception {
        Class<?> serverClass = Class.forName("com.meshql.server.Server");
        Object server = serverClass.getConstructor(Map.class).newInstance(plugins);
        serverClass.getMethod("init", Config.class).invoke(server, buildConfig());
        awaitReady();
        return server;
    }

    private void awaitReady() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(platformUrl() + "/health"))
                .timeout(Duration.ofSeconds(1)).GET().build();
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Server on port " + port + " never became ready", last);
    }
}
