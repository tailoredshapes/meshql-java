package com.meshql.repos.sqlite.authz;

import com.meshql.auth.noop.NoAuth;
import com.meshql.cert.AuthzCredentials;
import com.meshql.cert.AuthzEnv;
import com.meshql.cert.AuthzQueries;
import com.meshql.cert.AuthzWorld;
import com.meshql.core.Plugin;
import com.meshql.repos.sqlite.SQLiteConfig;
import com.meshql.repos.sqlite.SQLitePlugin;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cucumber hooks for the SQLite authorization certification.
 *
 * <p>Note that the plugin is constructed with {@link NoAuth}, exactly as the other
 * SQLite certification hooks do. Authorization is configured on the server, via
 * {@code Config.casbinParams}. If the server does not push that configured Auth all
 * the way down to the Searcher, the certification fails - which is the point.
 */
public class SQLiteAuthzHooks {
    private static final Logger logger = LoggerFactory.getLogger(SQLiteAuthzHooks.class);
    private static final int PORT = 4047;

    private static String dbFile;
    private static String table;
    private static Path schemaFile;
    private static AuthzEnv env;
    private static Object server;
    private static Map<String, Plugin> plugins;
    private static SQLiteConfig storage;

    private final AuthzWorld world;

    public SQLiteAuthzHooks(AuthzWorld world) {
        this.world = world;
    }

    @BeforeAll
    public static void setUpAll() throws Exception {
        logger.info("BeforeAll: setting up SQLite authorization certification");

        Path tempDb = Files.createTempFile("sqlite_authz_test_", ".db");
        tempDb.toFile().deleteOnExit();
        dbFile = tempDb.toString();

        table = "doc_" + String.valueOf(System.currentTimeMillis()).substring(8);
        storage = new SQLiteConfig(dbFile, table);

        schemaFile = AuthzEnv.writeSchemaFile();

        List<String> casbinParams = AuthzCredentials.writeCasbinParams();

        env = new AuthzEnv(
                "http://localhost:" + PORT,
                PORT,
                schemaFile,
                storage,
                AuthzQueries.sqliteQueries(),
                casbinParams
        );

        plugins = new HashMap<>();
        plugins.put("sqlite", new SQLitePlugin());

        // Creates the table.
        plugins.get("sqlite").createRepository(storage);

        server = env.buildService(plugins);

        logger.info("SQLite authorization certification ready on port {}", PORT);
    }

    @Before
    public void setUp() {
        world.env = env;
        world.server = server;
        world.plugins = plugins;
        world.repository = plugins.get("sqlite").createRepository(storage);
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
        if (schemaFile != null) {
            Files.deleteIfExists(schemaFile);
        }
        logger.info("SQLite authorization certification shut down");
    }
}
