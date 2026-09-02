package com.meshql.repos.sqlite;

import com.meshql.auth.noop.NoAuth;
import com.meshql.cert.FarmEnv;
import com.meshql.cert.FarmWorld;
import com.meshql.core.Plugin;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Cucumber hooks for SQLite farm certification tests.
 * Matches PostgreSQL pattern: BeforeAll creates shared environment, Before injects references.
 */
public class SQLiteFarmHooks {
    private static final Logger logger = LoggerFactory.getLogger(SQLiteFarmHooks.class);
    private static final int PORT = 4046;

    // GraphQL schemas (same as PostgreSQL)
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
    private static String dbFile;
    private static String farmTable;
    private static String coopTable;
    private static String henTable;
    private static Path farmSchemaFile;
    private static Path coopSchemaFile;
    private static Path henSchemaFile;
    private static FarmEnv farmEnv;
    private static Object server;
    private static Map<String, Plugin> plugins;

    // Instance field for world (injected by Cucumber)
    private final FarmWorld world;

    public SQLiteFarmHooks(FarmWorld world) {
        this.world = world;
    }

    @BeforeAll
    public static void setUpAll() throws Exception {
        logger.info("BeforeAll: Setting up SQLite farm test environment");

        // Use temporary file-based database for tests
        // Note: Cannot use :memory: because each connection gets a separate database
        Path tempDb = Files.createTempFile("sqlite_farm_test_", ".db");
        tempDb.toFile().deleteOnExit();
        dbFile = tempDb.toString();

        // Create unique table names for this test run
        String testId = String.valueOf(System.currentTimeMillis()).substring(8);
        farmTable = "farm_" + testId;
        coopTable = "coop_" + testId;
        henTable = "hen_" + testId;

        logger.info("Using SQLite in-memory database with tables: {}, {}, {}", farmTable, coopTable, henTable);

        // Write schemas to temp files
        farmSchemaFile = Files.createTempFile("farm_schema", ".graphql");
        coopSchemaFile = Files.createTempFile("coop_schema", ".graphql");
        henSchemaFile = Files.createTempFile("hen_schema", ".graphql");

        Files.writeString(farmSchemaFile, FARM_SCHEMA);
        Files.writeString(coopSchemaFile, COOP_SCHEMA);
        Files.writeString(henSchemaFile, HEN_SCHEMA);

        // Create storage configs
        SQLiteConfig farmStorage = new SQLiteConfig(dbFile, farmTable);
        SQLiteConfig coopStorage = new SQLiteConfig(dbFile, coopTable);
        SQLiteConfig henStorage = new SQLiteConfig(dbFile, henTable);

        // Create FarmEnv with SQLite queries
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
                com.meshql.cert.FarmQueries.sqliteQueries()
        );

        // Create plugins
        plugins = new HashMap<>();
        plugins.put("sqlite", new SQLitePlugin());

        // Initialize tables before starting server
        SQLiteRepository farmRepo = (SQLiteRepository) plugins.get("sqlite").createRepository(farmStorage);
        SQLiteRepository coopRepo = (SQLiteRepository) plugins.get("sqlite").createRepository(coopStorage);
        SQLiteRepository henRepo = (SQLiteRepository) plugins.get("sqlite").createRepository(henStorage);

        // Note: SQLite repositories initialize themselves in createRepository
        logger.info("Initialized SQLite tables: {}, {}, {}", farmTable, coopTable, henTable);

        // Build and start server
        server = farmEnv.buildService(plugins);

        logger.info("SQLite farm test environment ready on port {}", PORT);
    }

    @Before
    public void setUp() throws Exception {
        logger.debug("Before: Injecting shared environment into scenario");

        // Inject shared environment and server
        world.env = farmEnv;
        world.server = server;

        // Set up repositories for direct entity creation (through plugin)
        SQLiteConfig farmConfig = new SQLiteConfig(dbFile, farmTable);
        SQLiteConfig coopConfig = new SQLiteConfig(dbFile, coopTable);
        SQLiteConfig henConfig = new SQLiteConfig(dbFile, henTable);

        world.repositories.put("farm", plugins.get("sqlite").createRepository(farmConfig));
        world.repositories.put("coop", plugins.get("sqlite").createRepository(coopConfig));
        world.repositories.put("hen", plugins.get("sqlite").createRepository(henConfig));

        world.plugins = plugins;
    }

    @After
    public void tearDown() {
        // Per-scenario cleanup if needed
    }

    @AfterAll
    public static void tearDownAll() throws Exception {
        logger.info("AfterAll: Cleaning up SQLite farm test environment");

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

        logger.info("SQLite farm test environment shut down");
    }
}
