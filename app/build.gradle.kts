plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "com.gavel"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // App dependencies
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.database)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.logging)
    implementation(libs.bundles.observability)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jwt)

    // Feature modules (app orchestrates, features are independent)
    implementation(project(":features:user-onboarding"))
    implementation(project(":features:product-catalog"))
    implementation(project(":features:bidding"))
    implementation(project(":features:settlement"))

    testImplementation(libs.bundles.testing)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.engine)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.gavel.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}
