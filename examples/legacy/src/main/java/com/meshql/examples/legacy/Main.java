package com.meshql.examples.legacy;

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

/**
 * Springfield Electric Anti-Corruption Layer
 *
 * Demonstrates MeshQL as an anti-corruption layer over a legacy database:
 * 1. Legacy PostgreSQL data is captured by Debezium CDC
 * 2. Changes flow through Kafka topics
 * 3. LegacyToCleanProcessor transforms and POSTs to MeshQL REST API
 * 4. Clean data is queryable via GraphQL/REST with full federation
 *
 * First example to use internal resolvers (no HTTP overhead between graphlettes).
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        logger.info("Starting Springfield Electric Anti-Corruption Layer");

        String mongoUri = getEnv("MONGO_URI", "mongodb://localhost:27017");
        String prefix = getEnv("PREFIX", "legacy");
        String env = getEnv("ENV", "development");
        int port = Integer.parseInt(getEnv("PORT", "4066"));
        String platformUrl = getEnv("PLATFORM_URL", "http://localhost:" + port);
        String kafkaBroker = getEnv("KAFKA_BROKER", "localhost:9092");

        logger.info("Configuration: mongoUri={}, prefix={}, env={}, port={}", mongoUri, prefix, env, port);
        logger.info("Kafka: broker={}", kafkaBroker);

        // Create storage configs
        MongoConfig customerDB = createMongoConfig(mongoUri, prefix, env, "customer");
        MongoConfig meterReadingDB = createMongoConfig(mongoUri, prefix, env, "meter_reading");
        MongoConfig billDB = createMongoConfig(mongoUri, prefix, env, "bill");
        MongoConfig paymentDB = createMongoConfig(mongoUri, prefix, env, "payment");

        // Build config with internal resolvers (first example to use these!)
        Config config = Config.builder()
                .port(port)
                // Customer graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/customer/graph")
                        .storage(customerDB)
                        .schema("/app/config/graph/customer.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .singleton("getByAccountNumber", "{\"payload.account_number\": \"{{account_number}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByStatus", "{\"payload.status\": \"{{status}}\"}")
                                .vector("getByRateClass", "{\"payload.rate_class\": \"{{rate_class}}\"}")
                                .internalVectorResolver("meterReadings", null, "getByCustomer", "/meter_reading/graph")
                                .internalVectorResolver("bills", null, "getByCustomer", "/bill/graph")
                                .internalVectorResolver("payments", null, "getByCustomer", "/payment/graph")))
                // MeterReading graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/meter_reading/graph")
                        .storage(meterReadingDB)
                        .schema("/app/config/graph/meter_reading.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByCustomer", "{\"payload.customer_id\": \"{{id}}\"}")
                                .internalSingletonResolver("customer", "customer_id", "getById", "/customer/graph")))
                // Bill graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/bill/graph")
                        .storage(billDB)
                        .schema("/app/config/graph/bill.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByCustomer", "{\"payload.customer_id\": \"{{id}}\"}")
                                .vector("getByStatus", "{\"payload.status\": \"{{status}}\"}")
                                .internalSingletonResolver("customer", "customer_id", "getById", "/customer/graph")
                                .internalVectorResolver("payments", null, "getByBill", "/payment/graph")))
                // Payment graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/payment/graph")
                        .storage(paymentDB)
                        .schema("/app/config/graph/payment.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByCustomer", "{\"payload.customer_id\": \"{{id}}\"}")
                                .vector("getByBill", "{\"payload.bill_id\": \"{{id}}\"}")
                                .internalSingletonResolver("customer", "customer_id", "getById", "/customer/graph")
                                .internalSingletonResolver("bill", "bill_id", "getById", "/bill/graph")))
                // Restlettes
                .restlette(RestletteConfig.builder()
                        .path("/customer/api")
                        .port(port)
                        .storage(customerDB)
                        .schema(loadJsonSchema("/app/config/json/customer.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/meter_reading/api")
                        .port(port)
                        .storage(meterReadingDB)
                        .schema(loadJsonSchema("/app/config/json/meter_reading.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/bill/api")
                        .port(port)
                        .storage(billDB)
                        .schema(loadJsonSchema("/app/config/json/bill.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/payment/api")
                        .port(port)
                        .storage(paymentDB)
                        .schema(loadJsonSchema("/app/config/json/payment.schema.json")))
                .build();

        // Create auth and plugins
        Auth auth = new NoAuth();
        Map<String, Plugin> plugins = new HashMap<>();
        plugins.put("mongo", new MongoPlugin());

        logger.info("Initializing server on port {}", port);

        Server server = new Server(plugins);
        server.init(config);

        logger.info("Server started successfully on port {}", port);

        // Start the Kafka processor
        IdResolver idResolver = new IdResolver(platformUrl);
        LegacyToCleanProcessor processor = new LegacyToCleanProcessor(
                kafkaBroker, platformUrl, idResolver);
        processor.start();

        logger.info("Legacy processor started");
        logger.info("Health check: http://localhost:{}/health", port);
        logger.info("GraphQL endpoints:");
        logger.info("  - http://localhost:{}/customer/graph", port);
        logger.info("  - http://localhost:{}/meter_reading/graph", port);
        logger.info("  - http://localhost:{}/bill/graph", port);
        logger.info("  - http://localhost:{}/payment/graph", port);
        logger.info("REST endpoints:");
        logger.info("  - http://localhost:{}/customer/api", port);
        logger.info("  - http://localhost:{}/meter_reading/api", port);
        logger.info("  - http://localhost:{}/bill/api", port);
        logger.info("  - http://localhost:{}/payment/api", port);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            try {
                processor.stop();
                logger.info("Processor stopped");
                server.stop();
                logger.info("Server stopped successfully");
            } catch (Exception e) {
                logger.error("Error during shutdown", e);
            }
        }));

        Thread.currentThread().join();
    }

    private static MongoConfig createMongoConfig(String uri, String prefix, String env, String entity) {
        MongoConfig config = new MongoConfig();
        config.uri = uri;
        config.db = prefix + "_" + env;
        config.collection = entity;
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
