package com.meshql.contract.memory;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * The shared contract's restlette JSON Schema suite ({@code @restlette}), run once
 * for meshql-java: validation happens in the restlette before anything reaches a
 * store, so it does not depend on the storage plugin, and the contract asks for one
 * run against any one store. The in-memory store is the cheapest. The scenario's
 * schema becomes the widget restlette's configured schema, on the same server and
 * hooks as the authorization suite. No per-backend runner selects
 * {@code features/schema}.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/schema")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.meshql.cert.authz,com.meshql.contract.memory")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, json:target/cucumber-reports/memory-contract-schema.json")
public class InMemoryContractSchemaTest {
}
