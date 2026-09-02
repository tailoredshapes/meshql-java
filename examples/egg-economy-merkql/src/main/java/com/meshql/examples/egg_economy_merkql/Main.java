package com.meshql.examples.egg_economy_merkql;

import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.core.Config;
import com.meshql.core.Plugin;
import com.meshql.core.config.*;
import com.meshql.repos.sqlite.SQLiteConfig;
import com.meshql.repos.sqlite.SQLitePlugin;
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
        logger.info("Starting Egg Economy merkql Application");

        int port = Integer.parseInt(getEnv("PORT", "5088"));
        String dataDir = getEnv("DATA_DIR", "data");

        // Ensure data directory exists
        new File(dataDir).mkdirs();

        String actorsDb = dataDir + "/actors.db";
        String eventsDb = dataDir + "/events.db";
        String projectionsDb = dataDir + "/projections.db";

        logger.info("Configuration: port={}, dataDir={}", port, dataDir);
        logger.info("SQLite files: actors={}, events={}, projections={}", actorsDb, eventsDb, projectionsDb);

        // Resolve config paths relative to working directory
        String configBase = getEnv("CONFIG_DIR", "config");

        // --- Actor storage configs (actors.db) ---
        SQLiteConfig farmDB = new SQLiteConfig(actorsDb, "farm", List.of("$.zone"));
        SQLiteConfig coopDB = new SQLiteConfig(actorsDb, "coop", List.of("$.farm_id"));
        SQLiteConfig henDB = new SQLiteConfig(actorsDb, "hen", List.of("$.coop_id"));
        SQLiteConfig containerDB = new SQLiteConfig(actorsDb, "container", List.of("$.zone"));
        SQLiteConfig consumerDB = new SQLiteConfig(actorsDb, "consumer", List.of("$.zone"));

        // --- Event storage configs (events.db) ---
        SQLiteConfig layReportDB = new SQLiteConfig(eventsDb, "lay_report", List.of("$.hen_id", "$.farm_id"));
        SQLiteConfig storageDepositDB = new SQLiteConfig(eventsDb, "storage_deposit", List.of("$.container_id"));
        SQLiteConfig storageWithdrawalDB = new SQLiteConfig(eventsDb, "storage_withdrawal", List.of("$.container_id"));
        SQLiteConfig containerTransferDB = new SQLiteConfig(eventsDb, "container_transfer", List.of("$.source_container_id", "$.dest_container_id"));
        SQLiteConfig consumptionReportDB = new SQLiteConfig(eventsDb, "consumption_report", List.of("$.consumer_id", "$.container_id"));

        // --- Projection storage configs (projections.db) ---
        SQLiteConfig containerInventoryDB = new SQLiteConfig(projectionsDb, "container_inventory", List.of("$.container_id"));
        SQLiteConfig henProductivityDB = new SQLiteConfig(projectionsDb, "hen_productivity", List.of("$.hen_id"));
        SQLiteConfig farmOutputDB = new SQLiteConfig(projectionsDb, "farm_output", List.of("$.farm_id"));

        Config config = Config.builder()
                .port(port)

                // ===== ACTOR GRAPHLETTES (5) =====

                .graphlette(GraphletteConfig.builder()
                        .path("/farm/graph")
                        .storage(farmDB)
                        .schema(configBase + "/graph/farm.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getAll", "1=1")
                                .vector("getByZone", "json_extract(payload, '$.zone') = '{{zone}}'")
                                .internalVectorResolver("coops", null, "getByFarm", "/coop/graph")
                                .internalVectorResolver("farmOutput", null, "getByFarm", "/farm_output/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/coop/graph")
                        .storage(coopDB)
                        .schema(configBase + "/graph/coop.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getAll", "1=1")
                                .vector("getByFarm", "json_extract(payload, '$.farm_id') = '{{id}}'")
                                .internalSingletonResolver("farm", "farm_id", "getById", "/farm/graph")
                                .internalVectorResolver("hens", null, "getByCoop", "/hen/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/hen/graph")
                        .storage(henDB)
                        .schema(configBase + "/graph/hen.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByCoop", "json_extract(payload, '$.coop_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("coop", "coop_id", "getById", "/coop/graph")
                                .internalVectorResolver("layReports", null, "getByHen", "/lay_report/graph")
                                .internalVectorResolver("productivity", null, "getByHen", "/hen_productivity/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/container/graph")
                        .storage(containerDB)
                        .schema(configBase + "/graph/container.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getAll", "1=1")
                                .vector("getByZone", "json_extract(payload, '$.zone') = '{{zone}}'")
                                .internalVectorResolver("inventory", null, "getByContainer", "/container_inventory/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/consumer/graph")
                        .storage(consumerDB)
                        .schema(configBase + "/graph/consumer.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getAll", "1=1")
                                .vector("getByZone", "json_extract(payload, '$.zone') = '{{zone}}'")
                                .internalVectorResolver("consumptionReports", null, "getByConsumer", "/consumption_report/graph")))

                // ===== EVENT GRAPHLETTES (5) =====

                .graphlette(GraphletteConfig.builder()
                        .path("/lay_report/graph")
                        .storage(layReportDB)
                        .schema(configBase + "/graph/lay_report.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByHen", "json_extract(payload, '$.hen_id') = '{{id}}'")
                                .vector("getByFarm", "json_extract(payload, '$.farm_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("hen", "hen_id", "getById", "/hen/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/storage_deposit/graph")
                        .storage(storageDepositDB)
                        .schema(configBase + "/graph/storage_deposit.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByContainer", "json_extract(payload, '$.container_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/storage_withdrawal/graph")
                        .storage(storageWithdrawalDB)
                        .schema(configBase + "/graph/storage_withdrawal.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByContainer", "json_extract(payload, '$.container_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/container_transfer/graph")
                        .storage(containerTransferDB)
                        .schema(configBase + "/graph/container_transfer.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getBySourceContainer", "json_extract(payload, '$.source_container_id') = '{{id}}'")
                                .vector("getByDestContainer", "json_extract(payload, '$.dest_container_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("sourceContainer", "source_container_id", "getById", "/container/graph")
                                .internalSingletonResolver("destContainer", "dest_container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/consumption_report/graph")
                        .storage(consumptionReportDB)
                        .schema(configBase + "/graph/consumption_report.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByConsumer", "json_extract(payload, '$.consumer_id') = '{{id}}'")
                                .vector("getByContainer", "json_extract(payload, '$.container_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("consumer", "consumer_id", "getById", "/consumer/graph")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                // ===== PROJECTION GRAPHLETTES (3) =====

                .graphlette(GraphletteConfig.builder()
                        .path("/container_inventory/graph")
                        .storage(containerInventoryDB)
                        .schema(configBase + "/graph/container_inventory.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByContainer", "json_extract(payload, '$.container_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/hen_productivity/graph")
                        .storage(henProductivityDB)
                        .schema(configBase + "/graph/hen_productivity.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByHen", "json_extract(payload, '$.hen_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("hen", "hen_id", "getById", "/hen/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/farm_output/graph")
                        .storage(farmOutputDB)
                        .schema(configBase + "/graph/farm_output.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByFarm", "json_extract(payload, '$.farm_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("farm", "farm_id", "getById", "/farm/graph")))

                // ===== RESTLETTES (13) =====

                .restlette(RestletteConfig.builder()
                        .path("/farm/api").port(port).storage(farmDB)
                        .schema(loadJsonSchema(configBase + "/json/farm.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/coop/api").port(port).storage(coopDB)
                        .schema(loadJsonSchema(configBase + "/json/coop.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/hen/api").port(port).storage(henDB)
                        .schema(loadJsonSchema(configBase + "/json/hen.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/container/api").port(port).storage(containerDB)
                        .schema(loadJsonSchema(configBase + "/json/container.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/consumer/api").port(port).storage(consumerDB)
                        .schema(loadJsonSchema(configBase + "/json/consumer.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/lay_report/api").port(port).storage(layReportDB)
                        .schema(loadJsonSchema(configBase + "/json/lay_report.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/storage_deposit/api").port(port).storage(storageDepositDB)
                        .schema(loadJsonSchema(configBase + "/json/storage_deposit.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/storage_withdrawal/api").port(port).storage(storageWithdrawalDB)
                        .schema(loadJsonSchema(configBase + "/json/storage_withdrawal.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/container_transfer/api").port(port).storage(containerTransferDB)
                        .schema(loadJsonSchema(configBase + "/json/container_transfer.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/consumption_report/api").port(port).storage(consumptionReportDB)
                        .schema(loadJsonSchema(configBase + "/json/consumption_report.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/container_inventory/api").port(port).storage(containerInventoryDB)
                        .schema(loadJsonSchema(configBase + "/json/container_inventory.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/hen_productivity/api").port(port).storage(henProductivityDB)
                        .schema(loadJsonSchema(configBase + "/json/hen_productivity.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/farm_output/api").port(port).storage(farmOutputDB)
                        .schema(loadJsonSchema(configBase + "/json/farm_output.schema.json")))

                .build();

        Auth auth = new NoAuth();
        Map<String, Plugin> plugins = new HashMap<>();
        plugins.put("sqlite", new SQLitePlugin());

        logger.info("Initializing server on port {}", port);

        Server server = new Server(plugins);
        server.init(config);

        logger.info("Server started successfully on port {}", port);
        logger.info("Health check: http://localhost:{}/health", port);
        logger.info("No event processor — Rust CDC processor handles projections externally");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            try {
                server.stop();
                logger.info("Server stopped successfully");
            } catch (Exception e) {
                logger.error("Error during shutdown", e);
            }
        }));

        Thread.currentThread().join();
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
