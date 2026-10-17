package com.meshql.contract.sqlite;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * SQLite against the shared contract's egg economy feature: events are create-only, a
 * projection is closed to the front end, the graph federates across fourteen entities
 * and answers {@code at:}.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/egg-economy")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.meshql.cert.egg,com.meshql.contract.sqlite")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, json:target/cucumber-reports/sqlite-egg-economy.json")
public class SQLiteEggEconomyTest {
}
