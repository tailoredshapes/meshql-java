package com.meshql.repositories.postgres.authz;

import com.meshql.auth.noop.NoAuth;
import com.meshql.cert.AuthzCredentials;
import com.meshql.cert.AuthzEnv;
import com.meshql.cert.AuthzQueries;
import com.meshql.cert.AuthzWorld;
import com.meshql.core.Plugin;
import com.meshql.repositories.postgres.PostgresConfig;
import com.meshql.repositories.postgres.PostgresPlugin;
import com.meshql.repositories.postgres.PostgresRepository;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cucumber hooks for the PostgreSQL authorization certification.
 *
 * <p>The plugin is constructed with {@link NoAuth}. Authorization is configured on
 * the server via {@code Config.casbinParams}, and the server is responsible for
 * pushing that Auth down to both the restlette and the Searcher.
 */
public class PostgresAuthzHooks {
    private static final Logger logger = LoggerFactory.getLogger(PostgresAuthzHooks.class);
    private static final int PORT = 4049;

    private static PostgreSQLContainer container;
    private static PostgresConfig storage;
    private static Path schemaFile;
    private static AuthzEnv env;
    private static Object server;
    private static Map<String, Plugin> plugins;

    private final AuthzWorld world;

    public PostgresAuthzHooks(AuthzWorld world) {
        this.world = world;
    }

    @BeforeAll
    public static void setUpAll() throws Exception {
        logger.info("BeforeAll: setting up PostgreSQL authorization certification");

        container = new PostgreSQLContainer("postgres:14-alpine")
                .withDatabaseName("authz_test_db")
                .withUsername("test")
                .withPassword("test");
        container.start();

        storage = new PostgresConfig();
        storage.uri = container.getJdbcUrl();
        storage.username = container.getUsername();
        storage.password = container.getPassword();
        storage.table = "doc_" + String.valueOf(System.currentTimeMillis()).substring(8);

        schemaFile = AuthzEnv.writeSchemaFile();
        List<String> casbinParams = AuthzCredentials.writeCasbinParams();

        env = new AuthzEnv(
                "http://localhost:" + PORT,
                PORT,
                schemaFile,
                storage,
                AuthzQueries.postgresQueries(),
                casbinParams
        );

        plugins = new HashMap<>();
        plugins.put("postgres", new PostgresPlugin());

        // PostgreSQL tables are not created lazily; initialize before the server starts.
        ((PostgresRepository) plugins.get("postgres").createRepository(storage)).initialize();

        server = env.buildService(plugins);

        logger.info("PostgreSQL authorization certification ready on port {}", PORT);
    }

    @Before
    public void setUp() {
        world.env = env;
        world.server = server;
        world.plugins = plugins;
        world.repository = plugins.get("postgres").createRepository(storage);
    }

    @AfterAll
    public static void tearDownAll() throws Exception {
        if (server != null) {
            server.getClass().getMethod("stop").invoke(server);
        }
        if (plugins != null) {
            for (Plugin plugin : plugins.values()) {
                plugin.cleanUp();
            }
        }
        if (container != null) {
            container.stop();
        }
        if (schemaFile != null) {
            Files.deleteIfExists(schemaFile);
        }
        logger.info("PostgreSQL authorization certification shut down");
    }
}
