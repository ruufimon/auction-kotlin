dependencies {
    implementation(project(":bank-api:web"))
    implementation(project(":features:user-onboarding"))
    // Only the api package (event payload types) may be used; enforced by architecture tests.
    implementation(project(":features:product-catalog"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)
}
