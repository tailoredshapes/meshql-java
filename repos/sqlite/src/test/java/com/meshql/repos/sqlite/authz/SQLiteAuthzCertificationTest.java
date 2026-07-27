package com.meshql.repos.sqlite.authz;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * SQLite authorization certification suite.
 *
 * <p>Drives the real HTTP surfaces with two ordinary callers to prove that
 * authorization is actually enforced, not merely configured.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/authz")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.meshql.cert.steps,com.meshql.repos.sqlite.authz")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, json:target/cucumber-reports/sqlite-authz-certification.json")
public class SQLiteAuthzCertificationTest {
}
