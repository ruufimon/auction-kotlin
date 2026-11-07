plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.gavel"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":kernel"))
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)
    implementation(libs.bundles.logging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.acceptance)
    testImplementation(testFixtures(project(":kernel")))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.jwt)
    testRuntimeOnly(libs.junit.engine)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Cucumber scenarios show up by their scenario names in reports.
    systemProperty("cucumber.junit-platform.naming-strategy", "long")
    // ./gradlew :features:user-onboarding:test -Dcucumber.filter.name="<scenario name>" -Dcucumber.plugin=pretty
    listOf("cucumber.filter.name", "cucumber.filter.tags", "cucumber.plugin").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
