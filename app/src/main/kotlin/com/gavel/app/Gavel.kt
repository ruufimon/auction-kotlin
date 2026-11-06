package com.gavel.app

import com.gavel.bidding.infrastructure.BiddingModule
import com.gavel.catalog.infrastructure.CatalogModule
import com.gavel.onboarding.application.EmailSender
import com.gavel.onboarding.application.OnboardingSettings
import com.gavel.onboarding.application.PasswordHasher
import com.gavel.onboarding.infrastructure.Argon2PasswordHasher
import com.gavel.onboarding.infrastructure.JwtSettings
import com.gavel.onboarding.infrastructure.OnboardingConfig
import com.gavel.onboarding.infrastructure.OnboardingModule
import com.gavel.onboarding.infrastructure.RefreshCookieSettings
import com.gavel.settlement.application.PaymentProvider
import com.gavel.settlement.application.SettlementSettings
import com.gavel.settlement.infrastructure.SandboxPaymentProvider
import com.gavel.settlement.infrastructure.SettlementModule
import io.micrometer.core.instrument.Gauge
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/** Assembles every module. Holds no business rules; it only wires implementations together. */
class Gavel(
    val config: AppConfig,
    val dataSource: DataSource,
    clock: Clock = Clock.systemUTC(),
    emailSender: EmailSender,
    hasher: PasswordHasher = Argon2PasswordHasher(),
    paymentProvider: PaymentProvider? = null,
    val meterRegistry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
) {
    private val database = connect(dataSource)
    val tx = ExposedTransactor(database)
    private val events = OutboxEventPublisher(clock)
    private val audit = DatabaseAuditLog(clock)
    private val metrics = MetricsAdapter(meterRegistry)

    val onboarding = OnboardingModule(
        tx, audit, events, clock,
        OnboardingConfig(
            jwt = JwtSettings(config.jwtSecret),
            cookie = RefreshCookieSettings(secure = config.cookieSecure, sameSite = if (config.cookieSecure) "None" else "Lax"),
            settings = OnboardingSettings(verificationLinkBase = "${config.webBaseUrl}/verify-email"),
        ),
        emailSender, hasher,
    )
    val bidding = BiddingModule(tx, onboarding.participants, config.money, events, audit, metrics, clock)
    val catalog = CatalogModule(tx, onboarding.participants, bidding.registry, config.money, events, audit, metrics, clock)
    val settlement = SettlementModule(
        tx, onboarding.participants,
        paymentProvider ?: SandboxPaymentProvider(config.paymentWebhookSecret, config.checkoutBaseUrl, clock),
        events, audit, metrics, clock, SettlementSettings(config.paymentWindow),
    )

    val dispatcher = OutboxDispatcher(tx, listOf(catalog.bidAcceptedHandler, settlement.auctionSoldHandler), metrics, clock)

    /** Called after the HTTP metrics plugin has configured the registry. */
    fun registerGauges() {
        Gauge.builder("gavel.outbox.pending") { dispatcher.pending.get() }.register(meterRegistry)
        Gauge.builder("gavel.outbox.dead") { dispatcher.dead.get() }.register(meterRegistry)
    }

    /** Runs every scheduled job once, in dependency order. Used by tests and the background loops. */
    fun runJobsOnce() {
        catalog.service.activateDue()
        catalog.service.closeDue()
        dispatcher.dispatchDue()
        settlement.service.expireOverdue()
    }

    val backgroundJobs = BackgroundJobs(
        listOf(
            "auction-lifecycle" to Duration.ofSeconds(2),
            "outbox" to Duration.ofMillis(500),
            "order-expiry" to Duration.ofSeconds(30),
            "outbox-prune" to Duration.ofHours(1),
        ),
    ) { job ->
        when (job) {
            "auction-lifecycle" -> {
                catalog.service.activateDue()
                catalog.service.closeDue()
            }
            "outbox" -> dispatcher.dispatchDue()
            "order-expiry" -> settlement.service.expireOverdue()
            "outbox-prune" -> dispatcher.pruneDelivered()
        }
    }
}
