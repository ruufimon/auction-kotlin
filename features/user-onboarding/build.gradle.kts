plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.gavel"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.database)
    implementation(libs.bundles.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.spring.security.crypto)

    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.junit.engine)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
