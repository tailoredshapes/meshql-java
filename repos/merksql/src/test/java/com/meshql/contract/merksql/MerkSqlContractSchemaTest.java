package com.meshql.contract.merksql;

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
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.meshql.cert.authz,com.meshql.contract.merksql")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, json:target/cucumber-reports/merksql-contract-schema.json")
public class MerkSqlContractSchemaTest {
}
