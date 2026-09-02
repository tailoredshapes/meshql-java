package com.meshql.contract.merksql;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * merkql against plugins no adapter can second-guess.
 *
 * <p>The other suites certify the default token plugin, against which an adapter
 * that reimplements the token rule and one that delegates are indistinguishable.
 * These scenarios run a plugin that refuses every read and one that authorizes on
 * a payload field, so only the adapter that actually asks passes.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/auth-plugin")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.meshql.cert.authz,com.meshql.cert.authplugin,com.meshql.contract.merksql")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, json:target/cucumber-reports/merksql-auth-plugin.json")
public class MerkSqlAuthPluginTest {
}
