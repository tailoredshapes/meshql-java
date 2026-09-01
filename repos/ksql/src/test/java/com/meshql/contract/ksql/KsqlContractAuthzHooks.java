package com.meshql.contract.ksql;

import com.meshql.auth.noop.NoAuth;
import com.meshql.cert.authz.ContractAuthzBackend;
import com.meshql.cert.authz.ContractAuthzHooks;
import com.meshql.cert.authz.ContractAuthzQueries;
import com.meshql.cert.authz.ContractAuthzWorld;
import com.meshql.repositories.ksql.KsqlConfig;
import com.meshql.repositories.ksql.KsqlPlugin;
import com.meshql.repositories.ksql.KsqlTestEnvironment;
import io.cucumber.java.After;
import io.cucumber.java.Before;

import java.util.UUID;

/**
 * ksqlDB hooks for the shared authorization contract.
 *
 * <p>Kafka and ksqlDB start once. A fresh store is a fresh topic, with the stream
 * and materialized table the plugin builds over it, so each scenario reads a log
 * no earlier scenario wrote to. Topics are ephemeral with the containers, so
 * there is nothing to drop afterwards.
 *
 * <p>The plugin is built with {@link NoAuth}. Authorization is configured on the
 * server, through {@code Config.casbinParams}, and the server is what has to push
 * that Auth down to the restlette and the Searcher.
 */
public class KsqlContractAuthzHooks extends ContractAuthzHooks {

    public KsqlContractAuthzHooks(ContractAuthzWorld world) {
        super(world);
    }

    @Before
    public void setUp() throws Exception {
        KsqlTestEnvironment.start();
        startScenario();
    }

    @After
    public void tearDown() throws Exception {
        stopScenario();
    }

    @Override
    protected ContractAuthzBackend freshBackend() {
        String topic = "widget_authz_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        KsqlConfig storage = KsqlTestEnvironment.createConfig(topic);

        return new ContractAuthzBackend(
                new KsqlPlugin(new NoAuth()),
                storage,
                ContractAuthzQueries.ksqlQueries());
    }
}
