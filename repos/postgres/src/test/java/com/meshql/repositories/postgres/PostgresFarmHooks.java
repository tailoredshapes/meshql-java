package com.meshql.repositories.postgres;

import com.meshql.auth.noop.NoAuth;
import com.meshql.cert.FarmEnv;
import com.meshql.cert.FarmWorld;
import com.meshql.core.Plugin;
import com.meshql.core.Repository;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Cucumber hooks for PostgreSQL farm certification tests.
 * Matches MongoDB pattern: BeforeAll creates shared environment, Before injects references.
 */
public class PostgresFarmHooks {
    private static final Logger logger = LoggerFactory.getLogger(PostgresFarmHooks.class);
    private static final int PORT = 4045;

    // GraphQL schemas (same as MongoDB)
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
    private static PostgreSQLContainer postgresContainer;
    private static DataSource dataSource;
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

    public PostgresFarmHooks(FarmWorld world) {
        this.world = world;
    }

    @BeforeAll
    public static void setUpAll() throws Exception {
        logger.info("BeforeAll: Setting up PostgreSQL farm test environment");

        // Start PostgreSQL container
        postgresContainer = new PostgreSQLContainer("postgres:14-alpine")
                .withDatabaseName("farm_test_db")
                .withUsername("test")
                .withPassword("test");
        postgresContainer.start();

        // Create DataSource
        PGSimpleDataSource pgDataSource = new PGSimpleDataSource();
        pgDataSource.setUrl(postgresContainer.getJdbcUrl());
        pgDataSource.setUser(postgresContainer.getUsername());
        pgDataSource.setPassword(postgresContainer.getPassword());
        dataSource = pgDataSource;

        logger.info("Started PostgreSQL container at {}", postgresContainer.getJdbcUrl());

        // Create unique table names for this test run
        String testId = String.valueOf(System.currentTimeMillis()).substring(8);
        farmTable = "farm_" + testId;
        coopTable = "coop_" + testId;
        henTable = "hen_" + testId;

        // Write schemas to temp files
        farmSchemaFile = Files.createTempFile("farm_schema", ".graphql");
        coopSchemaFile = Files.createTempFile("coop_schema", ".graphql");
        henSchemaFile = Files.createTempFile("hen_schema", ".graphql");

        Files.writeString(farmSchemaFile, FARM_SCHEMA);
        Files.writeString(coopSchemaFile, COOP_SCHEMA);
        Files.writeString(henSchemaFile, HEN_SCHEMA);

        // Create storage configs
        PostgresConfig farmStorage = new PostgresConfig();
        farmStorage.uri = postgresContainer.getJdbcUrl();
        farmStorage.username = postgresContainer.getUsername();
        farmStorage.password = postgresContainer.getPassword();
        farmStorage.table = farmTable;

        PostgresConfig coopStorage = new PostgresConfig();
        coopStorage.uri = postgresContainer.getJdbcUrl();
        coopStorage.username = postgresContainer.getUsername();
        coopStorage.password = postgresContainer.getPassword();
        coopStorage.table = coopTable;

        PostgresConfig henStorage = new PostgresConfig();
        henStorage.uri = postgresContainer.getJdbcUrl();
        henStorage.username = postgresContainer.getUsername();
        henStorage.password = postgresContainer.getPassword();
        henStorage.table = henTable;

        // Create FarmEnv with PostgreSQL queries
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
                com.meshql.cert.FarmQueries.postgresQueries()
        );

        // Create plugins
        plugins = new HashMap<>();
        plugins.put("postgres", new PostgresPlugin());

        // Initialize tables before starting server
        PostgresRepository farmRepo = (PostgresRepository) plugins.get("postgres").createRepository(farmStorage);
        PostgresRepository coopRepo = (PostgresRepository) plugins.get("postgres").createRepository(coopStorage);
        PostgresRepository henRepo = (PostgresRepository) plugins.get("postgres").createRepository(henStorage);

        farmRepo.initialize();
        coopRepo.initialize();
        henRepo.initialize();

        logger.info("Initialized PostgreSQL tables: {}, {}, {}", farmTable, coopTable, henTable);

        // Build and start server
        server = farmEnv.buildService(plugins);

        logger.info("PostgreSQL farm test environment ready on port {}", PORT);
    }

    @Before
    public void setUp() throws Exception {
        logger.debug("Before: Injecting shared environment into scenario");

        // Inject shared environment and server
        world.env = farmEnv;
        world.server = server;

        // Set up repositories for direct entity creation (through plugin)
        PostgresConfig farmConfig = new PostgresConfig();
        farmConfig.uri = postgresContainer.getJdbcUrl();
        farmConfig.username = postgresContainer.getUsername();
        farmConfig.password = postgresContainer.getPassword();
        farmConfig.table = farmTable;

        PostgresConfig coopConfig = new PostgresConfig();
        coopConfig.uri = postgresContainer.getJdbcUrl();
        coopConfig.username = postgresContainer.getUsername();
        coopConfig.password = postgresContainer.getPassword();
        coopConfig.table = coopTable;

        PostgresConfig henConfig = new PostgresConfig();
        henConfig.uri = postgresContainer.getJdbcUrl();
        henConfig.username = postgresContainer.getUsername();
        henConfig.password = postgresContainer.getPassword();
        henConfig.table = henTable;

        world.repositories.put("farm", plugins.get("postgres").createRepository(farmConfig));
        world.repositories.put("coop", plugins.get("postgres").createRepository(coopConfig));
        world.repositories.put("hen", plugins.get("postgres").createRepository(henConfig));

        world.plugins = plugins;
    }

    @After
    public void tearDown() {
        // Per-scenario cleanup if needed
    }

    @AfterAll
    public static void tearDownAll() throws Exception {
        logger.info("AfterAll: Cleaning up PostgreSQL farm test environment");

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

        // Stop PostgreSQL container
        if (postgresContainer != null) {
            postgresContainer.stop();
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

        logger.info("PostgreSQL farm test environment shut down");
    }
}
