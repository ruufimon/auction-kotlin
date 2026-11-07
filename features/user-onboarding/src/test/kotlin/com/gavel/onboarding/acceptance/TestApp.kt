package com.gavel.onboarding.acceptance

import com.gavel.kernel.audit.DatabaseAuditLog
import com.gavel.kernel.auth.keycloak
import com.gavel.kernel.events.OutboxEventPublisher
import com.gavel.kernel.testing.TestDatabase
import com.gavel.kernel.web.installWebKernel
import com.gavel.onboarding.UserOnboarding
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.routing.routing

/**
 * The application as the acceptance tests see it: kernel plugins, token validation
 * trusting the scenario's identity provider, and the onboarding feature on the test database.
 */
fun Application.onboardingTestModule(world: World) {
    val onboarding = UserOnboarding(
        settings = world.settings,
        database = TestDatabase.database,
        eventPublisher = OutboxEventPublisher(world.clock),
        auditLog = DatabaseAuditLog(world.clock),
        clock = world.clock,
        identityProviderAdmin = world.idpAdmin,
        creditPolicy = { world.policy },
        sellerRequirements = { world.sellerRequirements },
    )
    installWebKernel { rateLimits(onboarding.rateLimits) }
    install(Authentication) {
        keycloak {
            issuer = world.idp.issuer
            audience = world.idp.audience
            jwkProvider = world.idp.jwkProvider
            clock = world.clock
        }
    }
    routing { onboarding.routes(this) }
}
