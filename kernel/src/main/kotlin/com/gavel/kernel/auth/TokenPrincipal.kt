package com.gavel.kernel.auth

import java.time.Instant

/**
 * Claims the application trusts from a validated identity-provider access token.
 * Authorization never relies on this alone: every protected endpoint also checks
 * the application account state (requirement §6.1).
 */
data class TokenPrincipal(
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val expiresAt: Instant,
)

/** Where tokens come from. [jwksUrl] defaults to Keycloak's certs endpoint for the realm. */
data class IdentityProviderSettings(
    val issuer: String,
    val audience: String = DEFAULT_AUDIENCE,
    val jwksUrl: String = "${issuer.trimEnd('/')}/protocol/openid-connect/certs",
) {
    companion object {
        const val DEFAULT_AUDIENCE = "gavel-api"
    }
}
