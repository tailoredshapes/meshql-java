package com.meshql.repositories.mongo;

import com.meshql.auth.noop.NoAuth;
import com.meshql.cert.FarmEnv;
import com.meshql.cert.FarmWorld;
import com.meshql.core.Plugin;
import com.meshql.core.Repository;
import com.meshql.server.Server;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.tailoredshapes.underbar.ocho.Die.rethrow;

/**
 * Cucumber hooks for MongoDB farm certification tests.
 * Matches TypeScript pattern: BeforeAll creates shared environment, Before injects references.
 */
public class MongoFarmHooks {
    private static final Logger logger = LoggerFactory.getLogger(MongoFarmHooks.class);
    private static final String DATABASE_NAME = "farm_test_db";
    private static final int PORT = 4044;

    // GraphQL schemas
    private static final String FARM_SCHEMA = """
        type Query {
          getById(id: ID, at: Float): Farm
        }

        type Farm {
          name: String!
          id: ID
          coops: [Coop]
        }

        type Coop {
          name: String!
          id: ID
          hens: [Hen]
        }

        type Hen {
          name: String!
          coop: Coop
          eggs: Int
          id: ID
        }
        """;

    private static final String COOP_SCHEMA = """
        type Farm {
          name: String!
          id: ID
        }

        type Query {
          getByName(name: String, at: Float): Coop
          getById(id: ID, at: Float): Coop
          getByFarm(id: ID, at: Float): [Coop]
        }

        type Coop {
          name: String!
          farm: Farm!
          id: ID
          hens: [Hen]
        }

        type Hen {
          name: String!
          eggs: Int
          id: ID
        }
        """;

    private static final String HEN_SCHEMA = """
        type Farm {
          name: String!
          id: ID
          coops: [Coop]
        }

        type Coop {
          name: String!
          farm: Farm!
          id: ID
        }

        type Query {
          getByName(name: String, at: Float): [Hen]
          getById(id: ID, at: Float): Hen
          getByCoop(id: ID, at: Float): [Hen]
        }

        type Hen {
          name: String!
          coop: Coop
          eggs: Int
          id: ID
        }
        """;

    // Global state - created once in BeforeAll, shared across all scenarios
    private static MongoServer mongoServer;
    private static MongoClient mongoClient;
    private static String farmCollection;
    private static String coopCollection;
    private static String henCollection;
    private static Path farmSchemaFile;
    private static Path coopSchemaFile;
    private static Path henSchemaFile;
    private static FarmEnv farmEnv;
    private static Object server;  // Object instead of Server to avoid compile-time dependency
    private static Map<String, Plugin> plugins;

    // Instance field for world (injected by Cucumber)
    private final FarmWorld world;

    /**
     * Constructor - Cucumber injects FarmWorld via PicoContainer.
     */
    public MongoFarmHooks(FarmWorld world) {
        this.world = world;
    }

    /**
     * BeforeAll - Create and start everything ONCE for all scenarios.
     * Matches TypeScript BeforeAll pattern.
     */
    @BeforeAll
    public static void setUpAll() throws Exception {
        logger.info("BeforeAll: Setting up MongoDB farm test environment");

        // Start in-memory MongoDB server
        mongoServer = new MongoServer(new MemoryBackend());
        String connectionString = mongoServer.bindAndGetConnectionString();
        mongoClient = MongoClients.create(connectionString);
        logger.info("Started in-memory MongoDB at {}", connectionString);

        // Create unique collection names for this test run
        String testId = String.valueOf(System.currentTimeMillis()).substring(8);
        farmCollection = "farm_" + testId;
        coopCollection = "coop_" + testId;
        henCollection = "hen_" + testId;

        // Write schemas to temp files
        farmSchemaFile = Files.createTempFile("farm_schema", ".graphql");
        coopSchemaFile = Files.createTempFile("coop_schema", ".graphql");
        henSchemaFile = Files.createTempFile("hen_schema", ".graphql");

        Files.writeString(farmSchemaFile, FARM_SCHEMA);
        Files.writeString(coopSchemaFile, COOP_SCHEMA);
        Files.writeString(henSchemaFile, HEN_SCHEMA);

        // Create storage configs
        MongoConfig farmStorage = new MongoConfig();
        farmStorage.uri = connectionString;
        farmStorage.db = DATABASE_NAME;
        farmStorage.collection = farmCollection;

        MongoConfig coopStorage = new MongoConfig();
        coopStorage.uri = connectionString;
        coopStorage.db = DATABASE_NAME;
        coopStorage.collection = coopCollection;

        MongoConfig henStorage = new MongoConfig();
        henStorage.uri = connectionString;
        henStorage.db = DATABASE_NAME;
        henStorage.collection = henCollection;

        // Create FarmEnv with MongoDB queries (matches TypeScript FarmEnv)
        String platformUrl = "http://localhost:" + PORT;
        farmEnv = new FarmEnv(
                platformUrl,
                PORT,
                farmSchemaFile,
                coopSchemaFile,
                henSchemaFile,
                farmStorage,
                coopStorage,
                henStorage,
                com.meshql.cert.FarmQueries.mongoQueries()
        );

        // Create plugins
        plugins = new HashMap<>();
        plugins.put("mongo", new MongoPlugin());

        // Create repositories for direct entity creation
        Repository farmRepo = plugins.get("mongo").createRepository(farmStorage);
        Repository coopRepo = plugins.get("mongo").createRepository(coopStorage);
        Repository henRepo = plugins.get("mongo").createRepository(henStorage);

        // Build and start server
        server = farmEnv.buildService(plugins);

        logger.info("MongoDB farm test environment ready on port {}", PORT);
    }

    /**
     * Before - Inject shared references into each scenario's world.
     * Matches TypeScript Before pattern.
     */
    @Before
    public void setUp() throws Exception {
        logger.debug("Before: Injecting shared environment into scenario");

        // Inject shared environment and server
        world.env = farmEnv;
        world.server = server;

        // Set up repositories for direct entity creation (legacy support)
        world.repositories.put("farm", plugins.get("mongo").createRepository(
                createFarmStorageConfig()
        ));
        world.repositories.put("coop", plugins.get("mongo").createRepository(
                createCoopStorageConfig()
        ));
        world.repositories.put("hen", plugins.get("mongo").createRepository(
                createHenStorageConfig()
        ));

        world.plugins = plugins;
    }

    /**
     * After - Clean up per-scenario resources (if any).
     * Matches TypeScript After pattern.
     */
    @After
    public void tearDown() {
        // Per-scenario cleanup if needed
        // Note: We do NOT drop collections or stop server here!
    }

    /**
     * AfterAll - Clean up global resources ONCE after all scenarios.
     * Matches TypeScript AfterAll pattern.
     */
    @AfterAll
    public static void tearDownAll() throws Exception {
        logger.info("AfterAll: Cleaning up MongoDB farm test environment");

        // Stop server using reflection
        if (server != null) {
            server.getClass().getMethod("stop").invoke(server);
        }

        // Clean up plugins
        if (plugins != null) {
            for (Plugin plugin : plugins.values()) {
                plugin.cleanUp();
            }
        }

        // Close MongoDB client
        if (mongoClient != null) {
            mongoClient.close();
        }

        // Shutdown MongoDB server
        if (mongoServer != null) {
            mongoServer.shutdown();
        }

        // Clean up temp files
        if (farmSchemaFile != null) {
            Files.deleteIfExists(farmSchemaFile);
        }
        if (coopSchemaFile != null) {
            Files.deleteIfExists(coopSchemaFile);
        }
        if (henSchemaFile != null) {
            Files.deleteIfExists(henSchemaFile);
        }

        logger.info("MongoDB farm test environment shut down");
    }

    // Helper methods for storage configs (needed for repositories)
    private MongoConfig createFarmStorageConfig() {
        MongoConfig config = new MongoConfig();
        config.uri = mongoServer.getConnectionString();
        config.db = DATABASE_NAME;
        config.collection = farmCollection;
        return config;
    }

    private MongoConfig createCoopStorageConfig() {
        MongoConfig config = new MongoConfig();
        config.uri = mongoServer.getConnectionString();
        config.db = DATABASE_NAME;
        config.collection = coopCollection;
        return config;
    }

    private MongoConfig createHenStorageConfig() {
        MongoConfig config = new MongoConfig();
        config.uri = mongoServer.getConnectionString();
        config.db = DATABASE_NAME;
        config.collection = henCollection;
        return config;
    }
}
