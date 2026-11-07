package com.gavel

import com.gavel.kernel.audit.DatabaseAuditLog
import com.gavel.kernel.auth.IdentityProviderSettings
import com.gavel.kernel.auth.TestIdentityProvider
import com.gavel.kernel.events.OutboxEventPublisher
import com.gavel.kernel.testing.TestDatabase
import com.gavel.migration.FlywayConfig
import com.gavel.onboarding.UserOnboarding
import com.gavel.onboarding.config.UserOnboardingSettings
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The production composition root serves the onboarding feature end to end. */
@EnabledIf("dockerAvailable")
class AppCompositionTest {
    companion object {
        @JvmStatic
        fun dockerAvailable() = TestDatabase.dockerAvailable
    }

    private val idp = TestIdentityProvider()

    @Test
    fun `a Keycloak token bootstraps an account through the real module`() = testApplication {
        val database = TestDatabase.migrate(*FlywayConfig.LOCATIONS)
        TestDatabase.truncate("users", "event_outbox", "audit_log")
        val onboarding = UserOnboarding(
            settings = UserOnboardingSettings.fromEnv({ null }, production = false),
            database = database,
            eventPublisher = OutboxEventPublisher(),
            auditLog = DatabaseAuditLog(),
        )
        application {
            module(
                identity = IdentityProviderSettings(issuer = idp.issuer),
                onboarding = onboarding,
                jwkProvider = idp.jwkProvider,
            )
        }

        val anonymous = client.get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        val token = idp.mint(subject = "kc-app-1", email = "app@example.com", emailVerified = true)
        val me = client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, me.status, me.bodyAsText())
        assertContains(me.bodyAsText(), "\"accountState\":\"PENDING_CREDIT_ELIGIBILITY\"")
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM event_outbox WHERE event_type = 'user-onboarding.account-created'"))
    }
}
