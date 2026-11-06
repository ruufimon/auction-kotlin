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
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.database)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.logging)
    implementation(libs.kotlinx.serialization.json)

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
