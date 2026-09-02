package com.meshql.repositories.mongo;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import com.meshql.core.Searcher;
import com.meshql.repos.certification.SearcherCertification;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.tailoredshapes.stash.Stash;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.tailoredshapes.underbar.ocho.Die.rethrow;

/**
 * Certification test for the MongoDB searcher implementation.
 * Uses an in-memory MongoDB server for testing.
 */
public class MongoSearcherCertificationTest extends SearcherCertification {
    private static final Logger logger = LoggerFactory.getLogger(MongoSearcherCertificationTest.class);
    private static final String DATABASE_NAME = "test_db";

    private static MongoServer server;
    private static MongoClient mongoClient;
    private MongoCollection<Document> collection;


    private static void setupMongo() {
        try {
            server = new MongoServer(new MemoryBackend());

            // Start the server and get the connection string
            String connectionString = server.bindAndGetConnectionString();
            mongoClient = MongoClients.create(connectionString);

            logger.info("Connected to MongoDB server at {}", connectionString);
        } catch (Exception e) {
            logger.error("Failed to start MongoDB server", e);
            throw new RuntimeException("Failed to start MongoDB server", e);
        }
    }

    @Override
    public void init() {
        if (server == null) {
            setupMongo();
        }

        // Create a new database for testing
        MongoDatabase database = mongoClient.getDatabase(DATABASE_NAME);

        // Create a new collection with a random name for each test
        String collectionName = "test_collection_" + UUID.randomUUID().toString().replace("-", "");
        MongoCollection<Document> testCollection = database.getCollection(collectionName);

        this.templates = createTemplates();
        // Create the repository
        this.repository = new MongoRepository(testCollection);

        Auth noOpAuth = new NoAuth();

        this.searcher = new MongoSearcher(testCollection);

        logger.info("Initialized MongoDB repository and searcher with collection: {}", collectionName);
    }

    private static SearcherTemplates createTemplates() {
        Handlebars handlebars = new Handlebars();

        Template findById = rethrow(() -> handlebars.compileInline("{\"id\": \"{{id}}\"}"));
        Template findByName = rethrow(() -> handlebars.compileInline("{\"payload.name\": \"{{id}}\"}"));
        Template findAllByType = rethrow(() -> handlebars.compileInline("{\"payload.type\": \"{{id}}\"}"));
        Template findByNameAndType = rethrow(() -> handlebars
                .compileInline("{\"payload.name\": \"{{name}}\", \"payload.type\": \"{{type}}\"}"));

        return new SearcherTemplates(findById, findByName, findAllByType, findByNameAndType);
    }
}