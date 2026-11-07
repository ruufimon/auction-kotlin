package com.gavel.onboarding

import com.gavel.kernel.audit.AuditLog
import com.gavel.kernel.events.EventPublisher
import com.gavel.onboarding.config.UserOnboardingSettings
import com.gavel.onboarding.domain.CreditPolicy
import com.gavel.onboarding.domain.SellerRequirements
import com.gavel.onboarding.persistence.AesGcmFieldCipher
import com.gavel.onboarding.persistence.ExposedAccountRepository
import com.gavel.onboarding.service.AccountEventPublisher
import com.gavel.onboarding.service.AccountService
import com.gavel.onboarding.service.IdentityProviderAdmin
import com.gavel.onboarding.service.NotConfiguredIdentityProviderAdmin
import com.gavel.onboarding.web.BOOTSTRAP_RATE_LIMITER
import com.gavel.onboarding.web.userOnboardingRoutes
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimitConfig
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.Route
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.sql.Database
import java.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Composition root of the user-onboarding feature. The app constructs one and mounts
 * [routes]; the kernel plugins and the `keycloak` authentication must already be installed.
 */
class UserOnboarding(
    val settings: UserOnboardingSettings,
    database: Database,
    eventPublisher: EventPublisher,
    auditLog: AuditLog,
    clock: Clock = Clock.systemUTC(),
    meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    identityProviderAdmin: IdentityProviderAdmin = NotConfiguredIdentityProviderAdmin(),
    /** Indirections so operators (and tests) can change policy without restarting the aggregate code. */
    creditPolicy: () -> CreditPolicy = { settings.creditPolicy },
    sellerRequirements: () -> SellerRequirements = { settings.sellerRequirements },
) {
    val service = AccountService(
        database = database,
        repository = ExposedAccountRepository(AesGcmFieldCipher.fromBase64Key(settings.assessmentEncryptionKey)),
        events = AccountEventPublisher(eventPublisher),
        audit = auditLog,
        clock = clock,
        policy = creditPolicy,
        sellerRequirements = sellerRequirements,
        identityProvider = identityProviderAdmin,
        meterRegistry = meterRegistry,
    )

    /** Register inside `installWebKernel { rateLimits(...) }` (requirement §8.2). */
    val rateLimits: RateLimitConfig.() -> Unit = {
        register(RateLimitName(BOOTSTRAP_RATE_LIMITER)) {
            rateLimiter(limit = settings.bootstrapRateLimitPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }

    fun routes(route: Route) = route.userOnboardingRoutes(service, settings.internalApiToken)
}
