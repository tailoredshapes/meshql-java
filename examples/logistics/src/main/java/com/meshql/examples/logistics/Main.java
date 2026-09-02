package com.meshql.examples.logistics;

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
import java.util.*;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        logger.info("Starting Logistics Example Application");

        // Read environment variables with defaults
        String mongoUri = getEnv("MONGO_URI", "mongodb://localhost:27017");
        String prefix = getEnv("PREFIX", "logistics");
        String env = getEnv("ENV", "development");
        int port = Integer.parseInt(getEnv("PORT", "3044"));
        String platformUrl = getEnv("PLATFORM_URL", "http://localhost:" + port);

        logger.info("Configuration: mongoUri={}, prefix={}, env={}, port={}", mongoUri, prefix, env, port);

        // Create storage configs for each collection
        MongoConfig warehouseDB = createMongoConfig(mongoUri, prefix, env, "warehouse");
        MongoConfig shipmentDB = createMongoConfig(mongoUri, prefix, env, "shipment");
        MongoConfig packageDB = createMongoConfig(mongoUri, prefix, env, "package");
        MongoConfig trackingUpdateDB = createMongoConfig(mongoUri, prefix, env, "tracking_update");

        // Build config using fluent builders
        Config config = Config.builder()
                .port(port)
                // Warehouse graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/warehouse/graph")
                        .storage(warehouseDB)
                        .schema("/app/config/graph/warehouse.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByCity", "{\"payload.city\": \"{{city}}\"}")
                                .vector("getByState", "{\"payload.state\": \"{{state}}\"}")
                                .vectorResolver("shipments", null, "getByWarehouse", platformUrl + "/shipment/graph")
                                .vectorResolver("packages", null, "getByWarehouse", platformUrl + "/package/graph")))
                // Shipment graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/shipment/graph")
                        .storage(shipmentDB)
                        .schema("/app/config/graph/shipment.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByWarehouse", "{\"payload.warehouse_id\": \"{{id}}\"}")
                                .vector("getByStatus", "{\"payload.status\": \"{{status}}\"}")
                                .vector("getByCarrier", "{\"payload.carrier\": \"{{carrier}}\"}")
                                .singletonResolver("warehouse", "warehouse_id", "getById", platformUrl + "/warehouse/graph")
                                .vectorResolver("packages", null, "getByShipment", platformUrl + "/package/graph")))
                // Package graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/package/graph")
                        .storage(packageDB)
                        .schema("/app/config/graph/package.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .singleton("getByTrackingNumber", "{\"payload.tracking_number\": \"{{tracking_number}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByWarehouse", "{\"payload.warehouse_id\": \"{{id}}\"}")
                                .vector("getByShipment", "{\"payload.shipment_id\": \"{{id}}\"}")
                                .vector("getByRecipient", "{\"payload.recipient\": {\"$regex\": \"{{recipient}}\", \"$options\": \"i\"}}")
                                .singletonResolver("warehouse", "warehouse_id", "getById", platformUrl + "/warehouse/graph")
                                .singletonResolver("shipment", "shipment_id", "getById", platformUrl + "/shipment/graph")
                                .vectorResolver("trackingUpdates", null, "getByPackage", platformUrl + "/tracking_update/graph")))
                // Tracking Update graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/tracking_update/graph")
                        .storage(trackingUpdateDB)
                        .schema("/app/config/graph/tracking_update.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByPackage", "{\"payload.package_id\": \"{{id}}\"}")
                                .singletonResolver("package", "package_id", "getById", platformUrl + "/package/graph")))
                // Restlettes
                .restlette(RestletteConfig.builder()
                        .path("/warehouse/api")
                        .port(port)
                        .storage(warehouseDB)
                        .schema(loadJsonSchema("/app/config/json/warehouse.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/shipment/api")
                        .port(port)
                        .storage(shipmentDB)
                        .schema(loadJsonSchema("/app/config/json/shipment.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/package/api")
                        .port(port)
                        .storage(packageDB)
                        .schema(loadJsonSchema("/app/config/json/package.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/tracking_update/api")
                        .port(port)
                        .storage(trackingUpdateDB)
                        .schema(loadJsonSchema("/app/config/json/tracking_update.schema.json")))
                .build();

        // Create authentication
        Auth auth = new NoAuth();

        // Register plugins
        Map<String, Plugin> plugins = new HashMap<>();
        plugins.put("mongo", new MongoPlugin());

        logger.info("Initializing server on port {}", port);

        // Create and initialize the server
        Server server = new Server(plugins);
        server.init(config);

        logger.info("Server started successfully on port {}", port);
        logger.info("Health check: http://localhost:{}/health", port);
        logger.info("GraphQL endpoints:");
        logger.info("  - http://localhost:{}/warehouse/graph", port);
        logger.info("  - http://localhost:{}/shipment/graph", port);
        logger.info("  - http://localhost:{}/package/graph", port);
        logger.info("  - http://localhost:{}/tracking_update/graph", port);
        logger.info("REST endpoints:");
        logger.info("  - http://localhost:{}/warehouse/api", port);
        logger.info("  - http://localhost:{}/shipment/api", port);
        logger.info("  - http://localhost:{}/package/api", port);
        logger.info("  - http://localhost:{}/tracking_update/api", port);

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
