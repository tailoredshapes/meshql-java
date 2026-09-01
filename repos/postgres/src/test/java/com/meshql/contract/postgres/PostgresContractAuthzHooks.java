package com.meshql.contract.postgres;

import com.meshql.auth.noop.NoAuth;
import com.meshql.cert.authz.ContractAuthzBackend;
import com.meshql.cert.authz.ContractAuthzHooks;
import com.meshql.cert.authz.ContractAuthzQueries;
import com.meshql.cert.authz.ContractAuthzWorld;
import com.meshql.repositories.postgres.PostgresConfig;
import com.meshql.repositories.postgres.PostgresPlugin;
import com.meshql.repositories.postgres.PostgresRepository;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

/**
 * PostgreSQL hooks for the shared authorization contract.
 *
 * <p>The container is expensive and starts once. The store inside it is cheap, so
 * each scenario gets a table of its own and starts against nothing.
 *
 * <p>The plugin is built with {@link NoAuth}. Authorization is configured on the
 * server, through {@code Config.casbinParams}, and the server is what has to push
 * that Auth down to the restlette and the Searcher.
 */
public class PostgresContractAuthzHooks extends ContractAuthzHooks {
    private static final Logger logger = LoggerFactory.getLogger(PostgresContractAuthzHooks.class);

    private static PostgreSQLContainer container;

    public PostgresContractAuthzHooks(ContractAuthzWorld world) {
        super(world);
    }

    @BeforeAll
    public static void startContainer() {
        container = new PostgreSQLContainer("postgres:14-alpine")
                .withDatabaseName("contract_authz_db")
                .withUsername("test")
                .withPassword("test");
        container.start();
        logger.info("PostgreSQL container started for the authorization contract");
    }

    @AfterAll
    public static void stopContainer() {
        if (container != null) {
            container.stop();
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
    protected ContractAuthzBackend freshBackend() throws Exception {
        PostgresConfig storage = new PostgresConfig();
        storage.uri = container.getJdbcUrl();
        storage.username = container.getUsername();
        storage.password = container.getPassword();
        storage.table = "widget_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        PostgresPlugin plugin = new PostgresPlugin(new NoAuth());
        // PostgreSQL tables are not created lazily, so build them before the
        // server takes its first request.
        ((PostgresRepository) plugin.createRepository(storage, null)).initialize();

        return new ContractAuthzBackend(plugin, storage, ContractAuthzQueries.postgresQueries());
    }
}
