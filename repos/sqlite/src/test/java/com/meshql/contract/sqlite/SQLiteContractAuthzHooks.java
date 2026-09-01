package com.meshql.contract.sqlite;

import com.meshql.auth.noop.NoAuth;
import com.meshql.cert.authz.ContractAuthzBackend;
import com.meshql.cert.authz.ContractAuthzHooks;
import com.meshql.cert.authz.ContractAuthzQueries;
import com.meshql.cert.authz.ContractAuthzWorld;
import com.meshql.repos.sqlite.SQLiteConfig;
import com.meshql.repos.sqlite.SQLitePlugin;
import io.cucumber.java.After;
import io.cucumber.java.Before;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * SQLite hooks for the shared authorization contract.
 *
 * <p>A fresh store is a fresh database file: SQLite has no server to share, so the
 * cheapest empty store is a new temp file with a new table in it.
 *
 * <p>The plugin is built with {@link NoAuth}. Authorization is configured on the
 * server, through {@code Config.casbinParams}, and the server is what has to push
 * that Auth down to the restlette and the Searcher. Passing it here instead would
 * certify the harness rather than the wiring.
 */
public class SQLiteContractAuthzHooks extends ContractAuthzHooks {

    public SQLiteContractAuthzHooks(ContractAuthzWorld world) {
        super(world);
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
        Path database = Files.createTempFile("sqlite_contract_authz_", ".db");
        database.toFile().deleteOnExit();
        String table = "widget_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        return new ContractAuthzBackend(
                new SQLitePlugin(new NoAuth()),
                new SQLiteConfig(database.toString(), table),
                ContractAuthzQueries.sqliteQueries());
    }
}
