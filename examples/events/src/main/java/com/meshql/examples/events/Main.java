package com.meshql.examples.events;

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
 * Events Example Application
 *
 * Demonstrates a CDC-powered event processing pipeline:
 * 1. Raw events are created via REST API
 * 2. MongoDB change streams are captured by Debezium
 * 3. Changes are published to Kafka
 * 4. RawToProcessedProcessor consumes and transforms events
 * 5. Processed events are stored and queryable via REST/GraphQL
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        logger.info("Starting Events Example Application");

        // Read environment variables with defaults
        String mongoUri = getEnv("MONGO_URI", "mongodb://localhost:27017/?replicaSet=rs0");
        String prefix = getEnv("PREFIX", "events");
        String env = getEnv("ENV", "development");
        int port = Integer.parseInt(getEnv("PORT", "4055"));
        String platformUrl = getEnv("PLATFORM_URL", "http://localhost:" + port);

        // Kafka configuration
        String kafkaBroker = getEnv("KAFKA_BROKER", "localhost:9092");
        String rawTopic = getEnv("RAW_TOPIC", prefix + "." + prefix + "_" + env + ".event");
        String processedApiBase = getEnv("PROCESSED_API_BASE", platformUrl + "/processedevent/api");

        logger.info("Configuration: mongoUri={}, prefix={}, env={}, port={}", mongoUri, prefix, env, port);
        logger.info("Kafka: broker={}, rawTopic={}", kafkaBroker, rawTopic);

        // Create storage configs for each collection
        MongoConfig eventDB = createMongoConfig(mongoUri, prefix, env, "event");
        MongoConfig processedEventDB = createMongoConfig(mongoUri, prefix, env, "processedevent");

        // Build config using fluent builders
        Config config = Config.builder()
                .port(port)
                // Event graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/event/graph")
                        .storage(eventDB)
                        .schema("/app/config/graph/event.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByName", "{\"payload.name\": \"{{name}}\"}")
                                .vectorResolver("processedEvents", "id", "getByRawEventId", platformUrl + "/processedevent/graph")))
                // ProcessedEvent graphlette
                .graphlette(GraphletteConfig.builder()
                        .path("/processedevent/graph")
                        .storage(processedEventDB)
                        .schema("/app/config/graph/processedevent.graphql")
                        .rootConfig(RootConfig.builder()
                                .singleton("getById", "{\"id\": \"{{id}}\"}")
                                .vector("getByName", "{\"payload.name\": \"{{name}}\"}")
                                .vector("getByRawEventId", "{\"payload.raw_event_id\": \"{{raw_event_id}}\"}")
                                .vector("getByEvent", "{\"payload.raw_event_id\": \"{{id}}\"}")
                                .singletonResolver("rawEvent", "raw_event_id", "getById", platformUrl + "/event/graph")))
                // Restlettes
                .restlette(RestletteConfig.builder()
                        .path("/event/api")
                        .port(port)
                        .storage(eventDB)
                        .schema(loadJsonSchema("/app/config/json/event.schema.json")))
                .restlette(RestletteConfig.builder()
                        .path("/processedevent/api")
                        .port(port)
                        .storage(processedEventDB)
                        .schema(loadJsonSchema("/app/config/json/processedevent.schema.json")))
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

        // Start the Kafka processor
        RawToProcessedProcessor processor = new RawToProcessedProcessor(
                kafkaBroker,
                rawTopic,
                processedApiBase
        );
        processor.start();

        logger.info("Kafka processor started");
        logger.info("Health check: http://localhost:{}/health", port);
        logger.info("GraphQL endpoints:");
        logger.info("  - http://localhost:{}/event/graph", port);
        logger.info("  - http://localhost:{}/processedevent/graph", port);
        logger.info("REST endpoints:");
        logger.info("  - http://localhost:{}/event/api", port);
        logger.info("  - http://localhost:{}/processedevent/api", port);

        // Add shutdown hook
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

        // Keep the main thread alive
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
