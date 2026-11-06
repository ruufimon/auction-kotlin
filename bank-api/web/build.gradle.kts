// Technical Ktor helpers shared by module transport adapters: error
// envelopes, authenticated-user lookup, and request parsing. No business rules.
dependencies {
    api(project(":bank-api"))
    api(libs.ktor.server.core)
    api(libs.ktor.server.auth)
    api(libs.ktor.server.content.negotiation)
    api(libs.ktor.serialization.json)
}
