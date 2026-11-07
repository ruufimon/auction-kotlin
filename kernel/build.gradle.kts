// Shared kernel: event, audit, web-error and token-validation primitives every
// feature may use. Features depend on :kernel; :kernel depends on no feature.
// Changes here are architecture changes (AGENT.md rule 5).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

group = "com.gavel"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.bundles.ktor.web)
    api(libs.kotlinx.serialization.json)
    api(libs.micrometer.core)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)
    implementation(libs.bundles.logging)
    implementation(libs.jwt)

    testFixturesApi(libs.bundles.testing)
    testFixturesImplementation(libs.bundles.database)
    testFixturesImplementation(libs.bundles.exposed)
    testFixturesImplementation(libs.jwt)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.engine)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
