package com.meshql.contract.sqlite;

import com.meshql.cert.egg.EggBackend;
import com.meshql.cert.egg.EggHooks;
import com.meshql.cert.egg.EggQueries;
import com.meshql.cert.egg.EggWorld;
import com.meshql.repos.sqlite.SQLiteConfig;
import com.meshql.repos.sqlite.SQLitePlugin;
import io.cucumber.java.After;
import io.cucumber.java.Before;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * SQLite hooks for the egg economy: a fresh economy is a new database file with a
 * table per entity.
 */
public class SQLiteEggEconomyHooks extends EggHooks {

    public SQLiteEggEconomyHooks(EggWorld world) {
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
    protected EggBackend freshBackend() throws Exception {
        Path database = Files.createTempFile("sqlite_egg_economy_", ".db");
        database.toFile().deleteOnExit();
        return new EggBackend(
                new SQLitePlugin(),
                entity -> new SQLiteConfig(database.toString(), entity),
                EggQueries.sqlite());
    }
}
