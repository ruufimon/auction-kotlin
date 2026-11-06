plugins {
    application
}

dependencies {
    implementation(project(":bank-api"))
    implementation(project(":bank-api:web"))
    implementation(project(":features:user-onboarding"))
    implementation(project(":features:product-catalog"))
    implementation(project(":features:bidding"))
    implementation(project(":features:settlement"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.prometheus)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.slf4j)
    implementation(libs.logback)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit)
}

application {
    applicationName = "gavel"
    mainClass.set("com.gavel.app.ApplicationKt")
}

// `./gradlew :app:run` from the repository root uses the root as working directory.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
