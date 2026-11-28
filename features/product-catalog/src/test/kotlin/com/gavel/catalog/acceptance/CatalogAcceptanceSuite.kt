package com.gavel.catalog.acceptance

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Runs the M3 catalog specification under src/test/resources/acceptance (AFA-90). Every
 * scenario starts tagged `@wip`; the item that implements a scenario removes the tag and adds
 * its step definitions to this package. Cucumber still parses every file, so a malformed
 * feature fails the build even while all scenarios are excluded.
 *
 * Excluded tags:
 *  - `@wip`           specified, not implemented yet
 *  - `@ui`            browser checks, covered by Playwright in web/e2e
 *  - `@cross-feature` needs bidding (M4) or settlement (M5)
 *  - `@gap`           behaviour that needs a decision first (docs/M3_CATALOG_ACCEPTANCE.md)
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("acceptance")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.gavel.catalog.acceptance")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @wip and not @ui and not @cross-feature and not @gap")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary")
class CatalogAcceptanceSuite
