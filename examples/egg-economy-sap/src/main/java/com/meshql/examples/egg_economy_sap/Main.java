package com.meshql.examples.egg_economy_sap;

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
 * Egg Economy SAP Anti-Corruption Layer
 *
 * Demonstrates MeshQL as an anti-corruption layer over a SAP-style legacy database:
 * 1. SAP PostgreSQL data is captured by Debezium CDC
 * 2. Changes flow through Kafka topics (10 topics, one per legacy table)
 * 3. LegacyToCleanProcessor transforms and POSTs to MeshQL REST API
 * 4. Clean data is queryable via GraphQL/REST with full federation
 * 5. Projections are updated inline (no MongoDB CDC needed)
 *
 * Single MongoDB instance, 13 graphlettes/restlettes, 5-phase processor startup.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        logger.info("Starting Egg Economy SAP Anti-Corruption Layer");

        String mongoUri = getEnv("MONGO_URI", "mongodb://localhost:27017");
        String prefix = getEnv("PREFIX", "egg_economy_sap");
        String env = getEnv("ENV", "development");
        int port = Integer.parseInt(getEnv("PORT", "5089"));
        String platformUrl = getEnv("PLATFORM_URL", "http://localhost:" + port);
        String kafkaBroker = getEnv("KAFKA_BROKER", "localhost:9092");

        logger.info("Configuration: mongoUri={}, prefix={}, env={}, port={}", mongoUri, prefix, env, port);
        logger.info("Kafka: broker={}", kafkaBroker);

        // Single MongoDB for all entities
        MongoConfig farmDB = createMongoConfig(mongoUri, prefix, env, "farm");
        MongoConfig coopDB = createMongoConfig(mongoUri, prefix, env, "coop");
        MongoConfig henDB = createMongoConfig(mongoUri, prefix, env, "hen");
        MongoConfig containerDB = createMongoConfig(mongoUri, prefix, env, "container");
        MongoConfig consumerDB = createMongoConfig(mongoUri, prefix, env, "consumer");
        MongoConfig layReportDB = createMongoConfig(mongoUri, prefix, env, "lay_report");
        MongoConfig storageDepositDB = createMongoConfig(mongoUri, prefix, env, "storage_deposit");
        MongoConfig storageWithdrawalDB = createMongoConfig(mongoUri, prefix, env, "storage_withdrawal");
        MongoConfig containerTransferDB = createMongoConfig(mongoUri, prefix, env, "container_transfer");
        MongoConfig consumptionReportDB = createMongoConfig(mongoUri, prefix, env, "consumption_report");
        MongoConfig containerInventoryDB = createMongoConfig(mongoUri, prefix, env, "container_inventory");
        MongoConfig henProductivityDB = createMongoConfig(mongoUri, prefix, env, "hen_productivity");
        MongoConfig farmOutputDB = createMongoConfig(mongoUri, prefix, env, "farm_output");

        Config config = Config.builder()
                .port(port)

                // ===== ACTOR GRAPHLETTES (5) =====

                .graphlette(GraphletteConfig.builder()
                        .path("/farm/graph")
                        .storage(farmDB)
                        .schema("/app/config/graph/farm.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByZone", "{\"payload.zone\": \"{{zone}}\"}")
                                .internalVectorResolver("coops", null, "getByFarm", "/coop/graph")
                                .internalVectorResolver("farmOutput", null, "getByFarm", "/farm_output/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/coop/graph")
                        .storage(coopDB)
                        .schema("/app/config/graph/coop.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByFarm", "{\"payload.farm_id\": \"{{id}}\"}")
                                .internalSingletonResolver("farm", "farm_id", "getById", "/farm/graph")
                                .internalVectorResolver("hens", null, "getByCoop", "/hen/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/hen/graph")
                        .storage(henDB)
                        .schema("/app/config/graph/hen.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByCoop", "{\"payload.coop_id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .internalSingletonResolver("coop", "coop_id", "getById", "/coop/graph")
                                .internalVectorResolver("layReports", null, "getByHen", "/lay_report/graph")
                                .internalVectorResolver("productivity", null, "getByHen", "/hen_productivity/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/container/graph")
                        .storage(containerDB)
                        .schema("/app/config/graph/container.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByZone", "{\"payload.zone\": \"{{zone}}\"}")
                                .internalVectorResolver("inventory", null, "getByContainer", "/container_inventory/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/consumer/graph")
                        .storage(consumerDB)
                        .schema("/app/config/graph/consumer.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .vector("getByZone", "{\"payload.zone\": \"{{zone}}\"}")
                                .internalVectorResolver("consumptionReports", null, "getByConsumer", "/consumption_report/graph")))

                // ===== EVENT GRAPHLETTES (5) =====

                .graphlette(GraphletteConfig.builder()
                        .path("/lay_report/graph")
                        .storage(layReportDB)
                        .schema("/app/config/graph/lay_report.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByHen", "{\"payload.hen_id\": \"{{id}}\"}")
                                .vector("getByFarm", "{\"payload.farm_id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .internalSingletonResolver("hen", "hen_id", "getById", "/hen/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/storage_deposit/graph")
                        .storage(storageDepositDB)
                        .schema("/app/config/graph/storage_deposit.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByContainer", "{\"payload.container_id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/storage_withdrawal/graph")
                        .storage(storageWithdrawalDB)
                        .schema("/app/config/graph/storage_withdrawal.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByContainer", "{\"payload.container_id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/container_transfer/graph")
                        .storage(containerTransferDB)
                        .schema("/app/config/graph/container_transfer.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getBySourceContainer", "{\"payload.source_container_id\": \"{{id}}\"}")
                                .vector("getByDestContainer", "{\"payload.dest_container_id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .internalSingletonResolver("sourceContainer", "source_container_id", "getById", "/container/graph")
                                .internalSingletonResolver("destContainer", "dest_container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/consumption_report/graph")
                        .storage(consumptionReportDB)
                        .schema("/app/config/graph/consumption_report.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByConsumer", "{\"payload.consumer_id\": \"{{id}}\"}")
                                .vector("getByContainer", "{\"payload.container_id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .internalSingletonResolver("consumer", "consumer_id", "getById", "/consumer/graph")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                // ===== PROJECTION GRAPHLETTES (3) =====

                .graphlette(GraphletteConfig.builder()
                        .path("/container_inventory/graph")
                        .storage(containerInventoryDB)
                        .schema("/app/config/graph/container_inventory.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByContainer", "{\"payload.container_id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/hen_productivity/graph")
                        .storage(henProductivityDB)
                        .schema("/app/config/graph/hen_productivity.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByHen", "{\"payload.hen_id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .internalSingletonResolver("hen", "hen_id", "getById", "/hen/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/farm_output/graph")
                        .storage(farmOutputDB)
                        .schema("/app/config/graph/farm_output.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByFarm", "{\"payload.farm_id\": \"{{id}}\"}")
                                .vector("getAll", "{}")
                                .internalSingletonResolver("farm", "farm_id", "getById", "/farm/graph")))

                // ===== RESTLETTES (13) =====

                .restlette(RestletteConfig.builder()
                        .path("/farm/api").port(port).storage(farmDB)
                        .schema(loadJsonSchema("/app/config/json/farm.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/coop/api").port(port).storage(coopDB)
                        .schema(loadJsonSchema("/app/config/json/coop.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/hen/api").port(port).storage(henDB)
                        .schema(loadJsonSchema("/app/config/json/hen.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/container/api").port(port).storage(containerDB)
                        .schema(loadJsonSchema("/app/config/json/container.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/consumer/api").port(port).storage(consumerDB)
                        .schema(loadJsonSchema("/app/config/json/consumer.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/lay_report/api").port(port).storage(layReportDB)
                        .schema(loadJsonSchema("/app/config/json/lay_report.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/storage_deposit/api").port(port).storage(storageDepositDB)
                        .schema(loadJsonSchema("/app/config/json/storage_deposit.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/storage_withdrawal/api").port(port).storage(storageWithdrawalDB)
                        .schema(loadJsonSchema("/app/config/json/storage_withdrawal.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/container_transfer/api").port(port).storage(containerTransferDB)
                        .schema(loadJsonSchema("/app/config/json/container_transfer.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/consumption_report/api").port(port).storage(consumptionReportDB)
                        .schema(loadJsonSchema("/app/config/json/consumption_report.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/container_inventory/api").port(port).storage(containerInventoryDB)
                        .schema(loadJsonSchema("/app/config/json/container_inventory.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/hen_productivity/api").port(port).storage(henProductivityDB)
                        .schema(loadJsonSchema("/app/config/json/hen_productivity.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/farm_output/api").port(port).storage(farmOutputDB)
                        .schema(loadJsonSchema("/app/config/json/farm_output.schema.json")))

                .build();

        Auth auth = new NoAuth();
        Map<String, Plugin> plugins = new HashMap<>();
        plugins.put("mongo", new MongoPlugin());

        logger.info("Initializing server on port {}", port);

        Server server = new Server(plugins);
        server.init(config);

        logger.info("Server started successfully on port {}", port);

        // Start the legacy processor
        IdResolver idResolver = new IdResolver(platformUrl);
        LegacyToCleanProcessor processor = new LegacyToCleanProcessor(
                kafkaBroker, platformUrl, idResolver);
        processor.start();

        logger.info("SAP legacy processor started");
        logger.info("Health check: http://localhost:{}/health", port);

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
