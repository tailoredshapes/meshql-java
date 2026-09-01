package com.meshql.contract.memory;

import com.meshql.cert.authz.ContractAuthzBackend;
import com.meshql.cert.authz.ContractAuthzHooks;
import com.meshql.cert.authz.ContractAuthzQueries;
import com.meshql.cert.authz.ContractAuthzWorld;
import com.meshql.core.config.StorageConfig;
import com.meshql.repositories.memory.InMemoryPlugin;
import io.cucumber.java.After;
import io.cucumber.java.Before;

/**
 * In-memory hooks for the shared authorization contract.
 *
 * <p>A plugin instance owns its store, so a new plugin is a new empty store. That
 * makes this the cheapest backend to isolate per scenario.
 *
 * <p>The suite lives in the server module rather than beside the plugin because
 * the restlette and graphlette use the in-memory plugin as their own test double.
 * That puts {@code memory} below {@code server} in the build, so a suite in
 * {@code repos/memory} that needs a running server would close a dependency cycle.
 */
public class InMemoryContractAuthzHooks extends ContractAuthzHooks {

    public InMemoryContractAuthzHooks(ContractAuthzWorld world) {
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
        return new ContractAuthzBackend(
                new InMemoryPlugin(),
                new StorageConfig("memory"),
                ContractAuthzQueries.memoryQueries());
    }
}
