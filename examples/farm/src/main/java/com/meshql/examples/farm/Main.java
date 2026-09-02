package com.meshql.examples.farm;

import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.core.Config;
import com.meshql.core.Plugin;
import com.meshql.core.config.*;
import com.meshql.repositories.mongo.MongoConfig;
import com.meshql.repositories.mongo.MongoPlugin;
import com.meshql.server.Server;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        logger.info("Starting Farm Example Application");

        // Read environment variables with defaults
        String mongoUri = getEnv("MONGO_URI", "mongodb://localhost:27017");
        String prefix = getEnv("PREFIX", "farm");
        String env = getEnv("ENV", "development");
        int port = Integer.parseInt(getEnv("PORT", "3033"));
        String platformUrl = getEnv("PLATFORM_URL", "http://localhost:" + port);

        logger.info("Configuration: mongoUri={}, prefix={}, env={}, port={}", mongoUri, prefix, env, port);

        // Create storage configs for each collection
        MongoConfig farmDB = createMongoConfig(mongoUri, prefix, env, "farm");
        MongoConfig coopDB = createMongoConfig(mongoUri, prefix, env, "coop");
        MongoConfig henDB = createMongoConfig(mongoUri, prefix, env, "hen");
        MongoConfig layReportDB = createMongoConfig(mongoUri, prefix, env, "lay_report");

        // Build config using fluent builders
        Config config = Config.builder()
                .port(port)
                // Farm graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/farm/graph")
                        .storage(farmDB)
                        .schema("/app/config/graph/farm.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vectorResolver("coops", null, "getByFarm", platformUrl + "/coop/graph")))
                // Coop graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/coop/graph")
                        .storage(coopDB)
                        .schema("/app/config/graph/coop.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getByName", "{\"payload.name\": \"{{id}}\"}")
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByFarm", "{\"payload.farm_id\": \"{{id}}\"}")
                                .singletonResolver("farm", "farm_id", "getById", platformUrl + "/farm/graph")
                                .vectorResolver("hens", null, "getByCoop", platformUrl + "/hen/graph")
                                .vectorResolver("hens.layReports", null, "getByHen", platformUrl + "/lay_report/graph")))
                // Hen graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/hen/graph")
                        .storage(henDB)
                        .schema("/app/config/graph/hen.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByName", "{\"payload.name\": \"{{name}}\"}")
                                .vector("getByCoop", "{\"payload.coop_id\": \"{{id}}\"}")
                                .singletonResolver("coop", "coop_id", "getById", platformUrl + "/coop/graph")
                                .vectorResolver("layReports", null, "getByHen", platformUrl + "/lay_report/graph")))
                // Lay Report graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/lay_report/graph")
                        .storage(layReportDB)
                        .schema("/app/config/graph/lay_report.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByHen", "{\"payload.hen_id\": \"{{id}}\"}")
                                .singletonResolver("hen", "hen_id", "getById", platformUrl + "/hen/graph")))
                // Restlettes
                .restlette(RestletteConfig.builder()
                        .path("/farm/api")
                        .port(port)
                        .storage(farmDB)
                        .schema(loadJsonSchema("/app/config/json/farm.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/coop/api")
                        .port(port)
                        .storage(coopDB)
                        .schema(loadJsonSchema("/app/config/json/coop.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/hen/api")
                        .port(port)
                        .storage(henDB)
                        .schema(loadJsonSchema("/app/config/json/hen.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/lay_report/api")
                        .port(port)
                        .storage(layReportDB)
                        .schema(loadJsonSchema("/app/config/json/lay_report.schema.json")))
                .build();

        // Create authentication
        Auth auth = new NoAuth();

        // Register plugins
        Map<String, Plugin> plugins = new HashMap<>();
        plugins.put("mongo", new MongoPlugin());

        logger.info("Initializing server on port {}", port);

        // Create and initialize the server
        Server server = new Server(plugins);
        String manifestJson = Files.readString(Paths.get("config", "manifest.json"));
        server.init(config, Map.of("/manifest", new ManifestServlet(manifestJson)));

        logger.info("Server started successfully on port {}", port);
        logger.info("Health check: http://localhost:{}/health", port);
        logger.info("GraphQL endpoints:");
        logger.info("  - http://localhost:{}/farm/graph", port);
        logger.info("  - http://localhost:{}/coop/graph", port);
        logger.info("  - http://localhost:{}/hen/graph", port);
        logger.info("  - http://localhost:{}/lay_report/graph", port);
        logger.info("Manifest: http://localhost:{}/manifest", port);

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down server...");
            try {
                server.stop();
                logger.info("Server stopped successfully");
            } catch (Exception e) {
                logger.error("Error during shutdown", e);
            }
        }));

        // Keep the main thread alive
        Thread.currentThread().join();
    }

    private static MongoConfig createMongoConfig(String uri, String prefix, String env, String entity) {
        MongoConfig config = new MongoConfig();
        config.uri = uri;
        config.db = prefix + "_" + env;
        config.collection = prefix + "-" + env + "-" + entity;
        return config;
    }

    private static JsonSchema loadJsonSchema(String path) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode schemaNode = mapper.readTree(new File(path));
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        return factory.getSchema(schemaNode);
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
