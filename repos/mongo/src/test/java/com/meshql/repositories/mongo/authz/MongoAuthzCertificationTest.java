package com.meshql.repositories.mongo.authz;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * MongoDB authorization certification suite.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/authz")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.meshql.cert.steps,com.meshql.repositories.mongo.authz")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, json:target/cucumber-reports/mongo-authz-certification.json")
public class MongoAuthzCertificationTest {
}
