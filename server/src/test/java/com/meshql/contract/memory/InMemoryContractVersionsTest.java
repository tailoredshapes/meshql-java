package com.meshql.contract.memory;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * The in-memory backend against the shared contract's document-versions suite.
 *
 * <p>It runs on the same server and the same hooks as the authorization suite,
 * because a version listing is an authorization surface: which versions carry a
 * URL is the same question the rest of that suite asks about records.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/versions")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.meshql.cert.authz,com.meshql.contract.memory")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, json:target/cucumber-reports/memory-contract-versions.json")
public class InMemoryContractVersionsTest {
}
