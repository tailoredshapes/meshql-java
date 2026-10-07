package com.meshql.cert.authz;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The server the shared authorization contract drives.
 *
 * <p>One entity, one restlette, one graphlette: the smallest surface that still
 * exercises the whole identity path — request header, Auth, envelope tokens,
 * storage, searcher, response. Writes go through the restlette and reads through
 * the graphlette, because the wiring between those surfaces and the Searcher is
 * where authorization gets dropped. A Repository-level certification structurally
 * cannot see it.
 *
 * <p>An instance covers a single scenario. The contract's {@code Background} has
 * no workspace step and assumes an empty store, so each scenario gets its own
 * store and its own server on its own port.
 */
public class ContractAuthzEnv {
    private static final Logger logger = LoggerFactory.getLogger(ContractAuthzEnv.class);

    /** How long to wait for a freshly started server to answer its health check. */
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);

    public static final String REST_PATH = "/widget/api";
    public static final String GRAPH_PATH = "/widget/graph";

    public static final String WIDGET_SCHEMA = """
            scalar Date

            type Query {
              getById(id: ID, at: Float): Widget
              getByKind(kind: String, at: Float): [Widget]
            }

            type Widget {
              id: ID
              name: String
              kind: String
              createdAt: Date
            }
            """;

    public static final String WIDGET_JSON_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "kind": { "type": "string" }
              },
              "required": ["name", "kind"],
              "additionalProperties": true
            }
            """;

    private final int port;
    private final Path schemaFile;
    private final StorageConfig storage;
    private final ContractAuthzQueries queries;
    private final List<String> casbinParams;

    public ContractAuthzEnv(
            int port,
            Path schemaFile,
            StorageConfig storage,
            ContractAuthzQueries queries,
            List<String> casbinParams
    ) {
        this.port = port;
        this.schemaFile = schemaFile;
        this.storage = storage;
        this.queries = queries;
        this.casbinParams = casbinParams;
    }

    /** Write the GraphQL schema to a temp file. Convenience for adapter hooks. */
    public static Path writeSchemaFile() throws IOException {
        Path file = Files.createTempFile("contract_authz_widget", ".graphql");
        file.toFile().deleteOnExit();
        Files.writeString(file, WIDGET_SCHEMA);
        return file;
    }

    public static JsonSchema jsonSchema() throws IOException {
        JsonNode node = new ObjectMapper().readTree(WIDGET_JSON_SCHEMA);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(node);
    }

    /** Claim a free port from the ephemeral range for one scenario's server. */
    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    public Config buildConfig() throws IOException {
        return buildConfig(jsonSchema());
    }

    /**
     * The configuration with the widget restlette validating against
     * {@code restletteSchema}: the same restlette configuration a deployment
     * writes, so {@code schema.feature} certifies the real validation path.
     */
    public Config buildConfig(JsonSchema restletteSchema) throws IOException {
        return Config.builder()
                .port(port)
                // Authorization is configured the documented way: Casbin params on the
                // server config. If the server does not honour them, every credentialed
                // scenario is unenforced and the contract says so.
                .casbinParams(casbinParams)
                .graphlette(GraphletteConfig.builder()
                        .path(GRAPH_PATH)
                        .storage(storage)
                        .schema(schemaFile.toString())
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", queries.widgetById)
                                .vector("getByKind", queries.widgetByKind)))
                .restlette(RestletteConfig.builder()
                        .path(REST_PATH)
                        .port(port)
                        .storage(storage)
                        .schema(restletteSchema))
                .build();
    }

    /**
     * Start the server. Uses reflection to avoid a compile-time dependency on the
     * server module, the same way the rest of the certification harness does.
     */
    public Object buildService(Map<String, Plugin> plugins) throws Exception {
        return buildService(plugins, null);
    }

    /**
     * Start the server, optionally with an auth plugin the caller names rather than
     * one the server resolves from configuration. Uses reflection to avoid a
     * compile-time dependency on the server module, the same way the rest of the
     * certification harness does.
     */
    public Object buildService(Map<String, Plugin> plugins, com.meshql.core.Auth auth) throws Exception {
        return buildService(plugins, auth, null);
    }

    /**
     * Start the server with the widget restlette validating against
     * {@code restletteSchema}, or against the permissive default when it is null.
     */
    public Object buildService(Map<String, Plugin> plugins, com.meshql.core.Auth auth, JsonSchema restletteSchema)
            throws Exception {
        Config config = buildConfig(restletteSchema == null ? jsonSchema() : restletteSchema);
        Class<?> serverClass = Class.forName("com.meshql.server.Server");
        Object server = serverClass
                .getConstructor(Map.class, com.meshql.core.Auth.class)
                .newInstance(plugins, auth);
        serverClass.getMethod("init", Config.class).invoke(server, config);
        awaitReady();
        logger.debug("Contract authorization server started on port {}", port);
        return server;
    }

    /** Poll the health endpoint until the connector answers, rather than sleeping. */
    private void awaitReady() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(platformUrl() + "/health"))
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build();
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Server on port " + port + " never became ready", last);
    }

    public int getPort() {
        return port;
    }

    public String platformUrl() {
        return "http://localhost:" + port;
    }

    public String restUrl() {
        return platformUrl() + REST_PATH;
    }

    public String graphUrl() {
        return platformUrl() + GRAPH_PATH;
    }
}
