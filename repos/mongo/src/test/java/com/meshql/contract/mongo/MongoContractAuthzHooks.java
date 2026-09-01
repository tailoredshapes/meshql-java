package com.meshql.contract.mongo;

import com.meshql.auth.noop.NoAuth;
import com.meshql.cert.authz.ContractAuthzBackend;
import com.meshql.cert.authz.ContractAuthzHooks;
import com.meshql.cert.authz.ContractAuthzQueries;
import com.meshql.cert.authz.ContractAuthzWorld;
import com.meshql.repositories.mongo.MongoConfig;
import com.meshql.repositories.mongo.MongoPlugin;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * MongoDB hooks for the shared authorization contract.
 *
 * <p>The embedded server starts once. A fresh store is a fresh collection inside
 * it, which is what gives each scenario the empty store the contract assumes.
 *
 * <p>The plugin is built with {@link NoAuth}. Authorization is configured on the
 * server, through {@code Config.casbinParams}, and the server is what has to push
 * that Auth down to the restlette and the Searcher.
 */
public class MongoContractAuthzHooks extends ContractAuthzHooks {
    private static final Logger logger = LoggerFactory.getLogger(MongoContractAuthzHooks.class);
    private static final String DATABASE_NAME = "contract_authz_db";

    private static MongoServer mongoServer;
    private static String connectionString;

    public MongoContractAuthzHooks(ContractAuthzWorld world) {
        super(world);
    }

    @BeforeAll
    public static void startMongo() {
        mongoServer = new MongoServer(new MemoryBackend());
        connectionString = mongoServer.bindAndGetConnectionString();
        logger.info("MongoDB started for the authorization contract at {}", connectionString);
    }

    @AfterAll
    public static void stopMongo() {
        if (mongoServer != null) {
            mongoServer.shutdown();
        }
    }

    @Before
    public void setUp() throws Exception {
        startScenario();
    }

    @After
    public void tearDown() throws Exception {
        stopScenario();
    }

    @Override
    protected ContractAuthzBackend freshBackend() {
        MongoConfig storage = new MongoConfig();
        storage.uri = connectionString;
        storage.db = DATABASE_NAME;
        storage.collection = "widget_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        return new ContractAuthzBackend(
                new MongoPlugin(new NoAuth()),
                storage,
                ContractAuthzQueries.mongoQueries());
    }
}
