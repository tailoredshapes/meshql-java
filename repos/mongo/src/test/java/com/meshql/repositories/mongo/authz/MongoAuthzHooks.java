package com.meshql.repositories.mongo.authz;

import com.meshql.auth.noop.NoAuth;
import com.meshql.cert.AuthzCredentials;
import com.meshql.cert.AuthzEnv;
import com.meshql.cert.AuthzQueries;
import com.meshql.cert.AuthzWorld;
import com.meshql.core.Plugin;
import com.meshql.repositories.mongo.MongoConfig;
import com.meshql.repositories.mongo.MongoPlugin;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
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
 * Cucumber hooks for the MongoDB authorization certification.
 *
 * <p>The plugin is constructed with {@link NoAuth}. Authorization is configured on
 * the server via {@code Config.casbinParams}, and the server is responsible for
 * pushing that Auth down to both the restlette and the Searcher.
 */
public class MongoAuthzHooks {
    private static final Logger logger = LoggerFactory.getLogger(MongoAuthzHooks.class);
    private static final String DATABASE_NAME = "authz_test_db";
    private static final int PORT = 4050;

    private static MongoServer mongoServer;
    private static MongoClient mongoClient;
    private static MongoConfig storage;
    private static Path schemaFile;
    private static AuthzEnv env;
    private static Object server;
    private static Map<String, Plugin> plugins;

    private final AuthzWorld world;

    public MongoAuthzHooks(AuthzWorld world) {
        this.world = world;
    }

    @BeforeAll
    public static void setUpAll() throws Exception {
        logger.info("BeforeAll: setting up MongoDB authorization certification");

        mongoServer = new MongoServer(new MemoryBackend());
        String connectionString = mongoServer.bindAndGetConnectionString();
        mongoClient = MongoClients.create(connectionString);

        storage = new MongoConfig();
        storage.uri = connectionString;
        storage.db = DATABASE_NAME;
        storage.collection = "doc_" + String.valueOf(System.currentTimeMillis()).substring(8);

        schemaFile = AuthzEnv.writeSchemaFile();
        List<String> casbinParams = AuthzCredentials.writeCasbinParams();

        env = new AuthzEnv(
                "http://localhost:" + PORT,
                PORT,
                schemaFile,
                storage,
                AuthzQueries.mongoQueries(),
                casbinParams
        );

        plugins = new HashMap<>();
        plugins.put("mongo", new MongoPlugin(new NoAuth()));
        plugins.get("mongo").createRepository(storage, null);

        server = env.buildService(plugins);

        logger.info("MongoDB authorization certification ready on port {}", PORT);
    }

    @Before
    public void setUp() {
        world.env = env;
        world.server = server;
        world.plugins = plugins;
        world.repository = plugins.get("mongo").createRepository(storage, null);
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
        if (mongoClient != null) {
            mongoClient.close();
        }
        if (mongoServer != null) {
            mongoServer.shutdown();
        }
        if (schemaFile != null) {
            Files.deleteIfExists(schemaFile);
        }
        logger.info("MongoDB authorization certification shut down");
    }
}
