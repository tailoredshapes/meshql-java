package com.meshql.contract.merksql;

import com.meshql.cert.authz.ContractAuthzBackend;
import com.meshql.cert.authz.ContractAuthzHooks;
import com.meshql.cert.authz.ContractAuthzQueries;
import com.meshql.cert.authz.ContractAuthzWorld;
import com.meshql.repositories.merksql.MerkSqlPlugin;
import io.cucumber.java.After;
import io.cucumber.java.Before;

import java.util.UUID;

/**
 * merksql hooks for the shared authorization contract.
 *
 * <p>{@code tempConfig} allocates a data directory per call, so a per-scenario
 * topic name is also a per-scenario broker directory and the store starts empty.
 *
 * <p>This module's tests are skipped unless {@code merksql.native.lib.path} points
 * at a built native library, so this suite compiles here but only runs where that
 * library exists.
 */
public class MerkSqlContractAuthzHooks extends ContractAuthzHooks {

    public MerkSqlContractAuthzHooks(ContractAuthzWorld world) {
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
    protected ContractAuthzBackend freshBackend() {
        String topic = "widget_authz_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        return new ContractAuthzBackend(
                new MerkSqlPlugin(),
                MerkSqlPlugin.tempConfig(topic),
                ContractAuthzQueries.memoryQueries());
    }
}
