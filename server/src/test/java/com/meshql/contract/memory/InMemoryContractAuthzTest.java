package com.meshql.contract.memory;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * The in-memory backend against the shared contract's authorization suite.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/authz-contract")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.meshql.cert.authz,com.meshql.contract.memory")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, json:target/cucumber-reports/memory-contract-authz.json")
public class InMemoryContractAuthzTest {
}
