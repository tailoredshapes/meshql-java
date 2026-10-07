package com.meshql.contract.postgres;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * This backend against the shared contract's restlette JSON Schema suite, on the
 * same server and hooks as the authorization suite: the scenario's schema becomes
 * the widget restlette's configured schema.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/schema")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.meshql.cert.authz,com.meshql.contract.postgres")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, json:target/cucumber-reports/postgres-contract-schema.json")
public class PostgresContractSchemaTest {
}
