dependencies {
    implementation(project(":bank-api:web"))
    implementation(project(":features:user-onboarding"))
    implementation(project(":features:bidding"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)
}
