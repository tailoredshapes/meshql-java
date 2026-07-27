package com.meshql.cert;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Test environment for the authorization certification.
 *
 * <p>Unlike the repository/searcher certifications, this one stands up the whole
 * server and drives the real HTTP surfaces: writes go through the restlette,
 * reads come back through the graphlette. That is deliberate. Every defect this
 * certification was written to catch lives in the wiring <em>above</em> the
 * Searcher — a Repository-level certification structurally cannot see them.
 */
public class AuthzEnv {
    private static final Logger logger = LoggerFactory.getLogger(AuthzEnv.class);

    public static final String DOC_SCHEMA = """
            scalar Date

            type Query {
              getById(id: ID, at: Float): Doc
              getByWorkspace(workspace: String, at: Float): [Doc]
            }

            type Doc {
              id: ID
              name: String
              workspace: String
              createdAt: Date
            }
            """;

    public static final String DOC_JSON_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "workspace": { "type": "string" }
              },
              "required": ["name", "workspace"],
              "additionalProperties": true
            }
            """;

    private final String platformUrl;
    private final int port;
    private final Path schemaFile;
    private final StorageConfig storage;
    private final AuthzQueries queries;
    private final List<String> casbinParams;

    public AuthzEnv(
            String platformUrl,
            int port,
            Path schemaFile,
            StorageConfig storage,
            AuthzQueries queries,
            List<String> casbinParams
    ) {
        this.platformUrl = platformUrl;
        this.port = port;
        this.schemaFile = schemaFile;
        this.storage = storage;
        this.queries = queries;
        this.casbinParams = casbinParams;
    }

    /**
     * Write the GraphQL schema to a temp file. Convenience for adapter hooks.
     */
    public static Path writeSchemaFile() throws Exception {
        Path file = Files.createTempFile("authz_doc_schema", ".graphql");
        file.toFile().deleteOnExit();
        Files.writeString(file, DOC_SCHEMA);
        return file;
    }

    public static JsonSchema jsonSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(DOC_JSON_SCHEMA);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(node);
    }

    public Config buildConfig() throws Exception {
        return Config.builder()
                .port(port)
                // Authorization is configured the documented way: Casbin params on the
                // server config. If the server does not honour them, every credentialed
                // scenario below is unenforced and this certification says so.
                .casbinParams(casbinParams)
                .graphlette(GraphletteConfig.builder()
                        .path("/doc/graph")
                        .storage(storage)
                        .schema(schemaFile.toString())
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", queries.docById)
                                .vector("getByWorkspace", queries.docByWorkspace)))
                .restlette(RestletteConfig.builder()
                        .path("/doc/api")
                        .port(port)
                        .storage(storage)
                        .schema(jsonSchema()))
                .build();
    }

    /**
     * Start the server. Uses reflection to avoid a compile-time dependency on the
     * server module (the same trick {@link FarmEnv} uses to keep cert dependency-free).
     */
    public Object buildService(Map<String, Plugin> plugins) throws Exception {
        Config config = buildConfig();
        Class<?> serverClass = Class.forName("com.meshql.server.Server");
        Object server = serverClass.getConstructor(Map.class).newInstance(plugins);
        serverClass.getMethod("init", Config.class).invoke(server, config);
        Thread.sleep(500);
        logger.info("Authz certification server started on port {}", port);
        return server;
    }

    public int getPort() {
        return port;
    }

    public String getPlatformUrl() {
        return platformUrl;
    }

    public String restUrl() {
        return platformUrl + "/doc/api";
    }

    public String graphUrl() {
        return platformUrl + "/doc/graph";
    }
}
