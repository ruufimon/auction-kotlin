package com.gavel.onboarding.acceptance

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Runs the feature files under src/test/resources/acceptance against the application only
 * (no Keycloak container). Excluded tags:
 *  - `@ui`            browser E2E suite (#42)
 *  - `@provider`      realm behaviour, run with Testcontainers Keycloak (#19)
 *  - `@cross-feature` needs bidding/catalog endpoints from later milestones
 *  - `@wip`           specified, not implemented yet; remove the tag as you implement
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("acceptance")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.gavel.onboarding.acceptance")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @ui and not @provider and not @cross-feature and not @wip")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary")
class AcceptanceSuite
