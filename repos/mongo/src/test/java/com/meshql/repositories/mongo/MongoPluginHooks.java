package com.meshql.repositories.mongo;

import com.github.jknack.handlebars.Handlebars;
import com.meshql.cert.Hooks;
import com.meshql.cert.IntegrationWorld;
import com.meshql.cert.SearcherTestTemplates;
import com.meshql.core.config.StorageConfig;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Cucumber hooks for MongoDB certification tests.
 * Sets up an in-memory MongoDB server for testing.
 */
public class MongoPluginHooks extends Hooks {
    private static final Logger logger = LoggerFactory.getLogger(MongoPluginHooks.class);
    private static final String DATABASE_NAME = "test_db";

    private static MongoServer server;
    private static MongoClient mongoClient;
    private MongoCollection<Document> collection;

    public MongoPluginHooks(IntegrationWorld world) {
        super(world);
    }

    @Before
    @Override
    public void setUp() {
        super.setUp(); // Sets up testStartTime, tokens, and auth

        try {
            // Start in-memory MongoDB server if not already running
            if (server == null) {
                server = new MongoServer(new MemoryBackend());
                String connectionString = server.bindAndGetConnectionString();
                mongoClient = MongoClients.create(connectionString);
                logger.info("Started in-memory MongoDB at {}", connectionString);
            }

            // Create a new collection for each scenario
            MongoDatabase database = mongoClient.getDatabase(DATABASE_NAME);
            String collectionName = "test_" + UUID.randomUUID().toString().replace("-", "");
            collection = database.getCollection(collectionName);

            // Configure the plugin
            MongoConfig config = new MongoConfig();
            config.uri = server.getConnectionString();
            config.db = DATABASE_NAME;
            config.collection = collectionName;

            this.world.storageConfig = config;
            this.world.plugin = new MongoPlugin();

            // Set up MongoDB query templates
            try {
                Handlebars handlebars = new Handlebars();
                this.world.templates = new SearcherTestTemplates(
                    // findById: { "id": "{{id}}" }
                    handlebars.compileInline("{ \"id\": \"{{id}}\" }"),

                    // findByName: { "payload.name": "{{id}}" }
                    handlebars.compileInline("{ \"payload.name\": \"{{id}}\" }"),

                    // findAllByType: { "payload.type": "{{id}}" }
                    handlebars.compileInline("{ \"payload.type\": \"{{id}}\" }"),

                    // findByNameAndType: { "payload.name": "{{name}}", "payload.type": "{{type}}" }
                    handlebars.compileInline("{ \"payload.name\": \"{{name}}\", \"payload.type\": \"{{type}}\" }")
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to compile MongoDB query templates", e);
            }

            logger.info("Initialized MongoDB certification tests with collection: {}", collectionName);
        } catch (Exception e) {
            logger.error("Failed to set up MongoDB for certification tests", e);
            throw new RuntimeException("MongoDB setup failed", e);
        }
    }

    @After
    public void tearDown() {
        // Collection is automatically cleaned up when scenario ends
        if (collection != null) {
            collection.drop();
        }
    }

    // Note: Cucumber doesn't have a global teardown hook, so we rely on JVM shutdown
    // The in-memory MongoDB server will be cleaned up when the JVM exits
}
