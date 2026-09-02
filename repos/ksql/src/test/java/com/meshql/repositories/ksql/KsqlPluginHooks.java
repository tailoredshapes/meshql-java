package com.meshql.repositories.ksql;

import com.github.jknack.handlebars.Handlebars;
import com.meshql.cert.Hooks;
import com.meshql.cert.IntegrationWorld;
import com.meshql.cert.SearcherTestTemplates;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class KsqlPluginHooks extends Hooks {
    private static final Logger logger = LoggerFactory.getLogger(KsqlPluginHooks.class);

    public KsqlPluginHooks(IntegrationWorld world) {
        super(world);
    }

    @Before
    @Override
    public void setUp() {
        super.setUp();

        try {
            KsqlTestEnvironment.start();

            String topic = "test_cuke_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

            KsqlConfig config = KsqlTestEnvironment.createConfig(topic);
            this.world.storageConfig = config;
            this.world.plugin = new KsqlPlugin();

            // Set up ksqlDB query templates
            Handlebars handlebars = new Handlebars();
            this.world.templates = new SearcherTestTemplates(
                    handlebars.compileInline("id = '{{id}}'"),
                    handlebars.compileInline("EXTRACTJSONFIELD(payload, '$.name') = '{{id}}'"),
                    handlebars.compileInline("EXTRACTJSONFIELD(payload, '$.type') = '{{id}}'"),
                    handlebars.compileInline(
                            "EXTRACTJSONFIELD(payload, '$.name') = '{{name}}' AND EXTRACTJSONFIELD(payload, '$.type') = '{{type}}'")
            );

            logger.info("Initialized ksqlDB certification tests with topic: {}", topic);
        } catch (Exception e) {
            logger.error("Failed to set up ksqlDB for certification tests", e);
            throw new RuntimeException("ksqlDB setup failed", e);
        }
    }

    @After
    public void tearDown() {
        // Topics are ephemeral per test - no explicit cleanup needed
    }
}
