package com.gavel.onboarding.service

import org.slf4j.LoggerFactory

/**
 * Admin operations on the identity provider used by suspension (USR-010). The Keycloak
 * admin-API implementation arrives with the realm deployment (#19); until then the
 * application state alone rejects tokens, which is the part the requirement makes immediate.
 */
interface IdentityProviderAdmin {
    /** Disables the user and ends its sessions so sign-in and refresh fail. */
    suspend fun disableUser(subject: String)
    suspend fun enableUser(subject: String)
}

class NotConfiguredIdentityProviderAdmin : IdentityProviderAdmin {
    private val log = LoggerFactory.getLogger(NotConfiguredIdentityProviderAdmin::class.java)

    override suspend fun disableUser(subject: String) {
        log.warn("Identity provider admin client not configured; provider account not disabled (retry via outbox handler)")
    }

    override suspend fun enableUser(subject: String) {
        log.warn("Identity provider admin client not configured; provider account not enabled (retry via outbox handler)")
    }
}
