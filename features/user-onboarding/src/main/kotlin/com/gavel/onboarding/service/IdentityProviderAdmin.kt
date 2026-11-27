package com.gavel.onboarding.service

import org.slf4j.LoggerFactory

/**
 * Admin operations on the identity provider used by suspension (USR-010). Production uses
 * [KeycloakIdentityProviderAdmin]; without admin credentials the application state alone
 * rejects tokens, which is the part the requirement makes immediate.
 */
interface IdentityProviderAdmin {
    /** Disables the user and ends its sessions so sign-in and refresh fail. */
    suspend fun disableUser(subject: String)
    suspend fun enableUser(subject: String)
}

class NotConfiguredIdentityProviderAdmin : IdentityProviderAdmin {
    private val log = LoggerFactory.getLogger(NotConfiguredIdentityProviderAdmin::class.java)

    override suspend fun disableUser(subject: String) {
        log.warn("Identity provider admin client not configured; provider account not disabled; set KEYCLOAK_ADMIN_CLIENT_ID/SECRET")
    }

    override suspend fun enableUser(subject: String) {
        log.warn("Identity provider admin client not configured; provider account not enabled; set KEYCLOAK_ADMIN_CLIENT_ID/SECRET")
    }
}
