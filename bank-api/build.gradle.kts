// Stable primitives shared by every module: identifiers, money, errors,
// pagination, clocks, event envelopes, and transaction/audit ports.
dependencies {
    api(libs.serialization.json)
    api(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.launcher)
}
