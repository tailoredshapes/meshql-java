package com.meshql.examples.egg_economy_ksqldb;

import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.core.Config;
import com.meshql.core.Plugin;
import com.meshql.core.config.*;
import com.meshql.repositories.ksql.KsqlConfig;
import com.meshql.repositories.ksql.KsqlPlugin;
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
        logger.info("Starting Egg Economy ksqlDB Application");

        String bootstrapServers = getEnv("BOOTSTRAP_SERVERS", "localhost:9092");
        String ksqlDbUrl = getEnv("KSQL_DB_URL", "http://localhost:8088");
        String prefix = getEnv("PREFIX", "eggs");
        String env = getEnv("ENV", "development");
        int port = Integer.parseInt(getEnv("PORT", "5090"));
        String platformUrl = getEnv("PLATFORM_URL", "http://localhost:" + port);
        String configBase = getEnv("CONFIG_BASE", "/app/config");

        logger.info("Configuration: prefix={}, env={}, port={}", prefix, env, port);
        logger.info("Kafka: bootstrap={}, ksqlDB={}", bootstrapServers, ksqlDbUrl);

        // --- Actor storage configs (5) ---
        KsqlConfig farmDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "farm");
        KsqlConfig coopDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "coop");
        KsqlConfig henDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "hen");
        KsqlConfig containerDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "container");
        KsqlConfig consumerDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "consumer");

        // --- Event storage configs (5) ---
        KsqlConfig layReportDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "lay_report");
        KsqlConfig storageDepositDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "storage_deposit");
        KsqlConfig storageWithdrawalDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "storage_withdrawal");
        KsqlConfig containerTransferDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "container_transfer");
        KsqlConfig consumptionReportDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "consumption_report");

        // --- Projection storage configs (3) ---
        KsqlConfig containerInventoryDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "container_inventory");
        KsqlConfig henProductivityDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "hen_productivity");
        KsqlConfig farmOutputDB = createKsqlConfig(bootstrapServers, ksqlDbUrl, prefix, env, "farm_output");

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
                                .vector("getByZone", "EXTRACTJSONFIELD(payload, '$.zone') = '{{zone}}'")
                                .internalVectorResolver("coops", null, "getByFarm", "/coop/graph")
                                .internalVectorResolver("farmOutput", null, "getByFarm", "/farm_output/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/coop/graph")
                        .storage(coopDB)
                        .schema(configBase + "/graph/coop.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getAll", "1=1")
                                .vector("getByFarm", "EXTRACTJSONFIELD(payload, '$.farm_id') = '{{id}}'")
                                .internalSingletonResolver("farm", "farm_id", "getById", "/farm/graph")
                                .internalVectorResolver("hens", null, "getByCoop", "/hen/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/hen/graph")
                        .storage(henDB)
                        .schema(configBase + "/graph/hen.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByCoop", "EXTRACTJSONFIELD(payload, '$.coop_id') = '{{id}}'")
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
                                .vector("getByZone", "EXTRACTJSONFIELD(payload, '$.zone') = '{{zone}}'")
                                .internalVectorResolver("inventory", null, "getByContainer", "/container_inventory/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/consumer/graph")
                        .storage(consumerDB)
                        .schema(configBase + "/graph/consumer.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getAll", "1=1")
                                .vector("getByZone", "EXTRACTJSONFIELD(payload, '$.zone') = '{{zone}}'")
                                .internalVectorResolver("consumptionReports", null, "getByConsumer", "/consumption_report/graph")))

                // ===== EVENT GRAPHLETTES (5) =====

                .graphlette(GraphletteConfig.builder()
                        .path("/lay_report/graph")
                        .storage(layReportDB)
                        .schema(configBase + "/graph/lay_report.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByHen", "EXTRACTJSONFIELD(payload, '$.hen_id') = '{{id}}'")
                                .vector("getByFarm", "EXTRACTJSONFIELD(payload, '$.farm_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("hen", "hen_id", "getById", "/hen/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/storage_deposit/graph")
                        .storage(storageDepositDB)
                        .schema(configBase + "/graph/storage_deposit.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByContainer", "EXTRACTJSONFIELD(payload, '$.container_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/storage_withdrawal/graph")
                        .storage(storageWithdrawalDB)
                        .schema(configBase + "/graph/storage_withdrawal.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByContainer", "EXTRACTJSONFIELD(payload, '$.container_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/container_transfer/graph")
                        .storage(containerTransferDB)
                        .schema(configBase + "/graph/container_transfer.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getBySourceContainer", "EXTRACTJSONFIELD(payload, '$.source_container_id') = '{{id}}'")
                                .vector("getByDestContainer", "EXTRACTJSONFIELD(payload, '$.dest_container_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("sourceContainer", "source_container_id", "getById", "/container/graph")
                                .internalSingletonResolver("destContainer", "dest_container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/consumption_report/graph")
                        .storage(consumptionReportDB)
                        .schema(configBase + "/graph/consumption_report.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByConsumer", "EXTRACTJSONFIELD(payload, '$.consumer_id') = '{{id}}'")
                                .vector("getByContainer", "EXTRACTJSONFIELD(payload, '$.container_id') = '{{id}}'")
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
                                .vector("getByContainer", "EXTRACTJSONFIELD(payload, '$.container_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("container", "container_id", "getById", "/container/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/hen_productivity/graph")
                        .storage(henProductivityDB)
                        .schema(configBase + "/graph/hen_productivity.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByHen", "EXTRACTJSONFIELD(payload, '$.hen_id') = '{{id}}'")
                                .vector("getAll", "1=1")
                                .internalSingletonResolver("hen", "hen_id", "getById", "/hen/graph")))

                .graphlette(GraphletteConfig.builder()
                        .path("/farm_output/graph")
                        .storage(farmOutputDB)
                        .schema(configBase + "/graph/farm_output.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "id = '{{id}}'")
                                .vector("getByFarm", "EXTRACTJSONFIELD(payload, '$.farm_id') = '{{id}}'")
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
        plugins.put("ksql", new KsqlPlugin());

        logger.info("Initializing server on port {}", port);

        Server server = new Server(plugins);
        server.init(config);

        logger.info("Server started successfully on port {}", port);

        // Start the event topic processor
        String topicPrefix = prefix + "-" + env;
        EventTopicProcessor processor = new EventTopicProcessor(
                bootstrapServers,
                topicPrefix,
                platformUrl
        );
        processor.start();

        logger.info("Event topic processor started");
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

    private static KsqlConfig createKsqlConfig(String bootstrapServers, String ksqlDbUrl,
                                                 String prefix, String env, String entity) {
        KsqlConfig config = new KsqlConfig();
        config.bootstrapServers = bootstrapServers;
        config.ksqlDbUrl = ksqlDbUrl;
        config.topic = prefix + "-" + env + "-" + entity;
        config.partitions = 1;
        config.replicationFactor = 1;
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
