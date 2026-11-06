package com.gavel.settlement.infrastructure

import com.gavel.kernel.CurrencyCode
import com.gavel.kernel.DomainException
import com.gavel.kernel.ErrorKind
import com.gavel.kernel.Money
import com.gavel.kernel.Secrets
import com.gavel.settlement.application.CheckoutRequest
import com.gavel.settlement.application.CheckoutSession
import com.gavel.settlement.application.PaymentProvider
import com.gavel.settlement.application.ProviderEvent
import com.gavel.settlement.application.ProviderOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A stand-in provider for development and tests. Its webhooks use the same
 * signing scheme a real adapter would: `Gavel-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">`.
 */
class SandboxPaymentProvider(
    private val webhookSecret: String,
    private val checkoutBaseUrl: String,
    private val clock: Clock,
    private val tolerance: Duration = Duration.ofMinutes(5),
) : PaymentProvider {
    override val name = "sandbox"

    init {
        require(webhookSecret.length >= 16) { "Payment webhook secret must be at least 16 characters" }
    }

    /** Deterministic per idempotency key, like a real provider honouring idempotency. */
    override fun createCheckout(request: CheckoutRequest): CheckoutSession {
        val reference = "sbx_" + Secrets.sha256Hex(request.idempotencyKey).take(24)
        return CheckoutSession(reference, "$checkoutBaseUrl/$reference")
    }

    override fun parseWebhook(headers: (String) -> String?, body: String): ProviderEvent {
        val header = headers(SIGNATURE_HEADER) ?: invalid()
        val parts = header.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val timestamp = parts["t"]?.toLongOrNull() ?: invalid()
        val signature = parts["v1"] ?: invalid()
        if (Duration.ofSeconds(kotlin.math.abs(clock.instant().epochSecond - timestamp)) > tolerance) invalid()
        if (!Secrets.constantTimeEquals(sign(webhookSecret, timestamp, body), signature)) invalid()

        val event = runCatching { json.decodeFromString(SandboxEvent.serializer(), body) }.getOrNull()
            ?: throw DomainException(ErrorKind.MALFORMED, "MALFORMED_REQUEST", "The webhook body is not a valid event.")
        val outcome = when (event.type) {
            "payment.succeeded" -> ProviderOutcome.SUCCEEDED
            "payment.failed" -> ProviderOutcome.FAILED
            else -> ProviderOutcome.IGNORED
        }
        val amount = event.data.amountMinor?.let { minor -> event.data.currency?.let { Money(minor, CurrencyCode(it)) } }
        return ProviderEvent(event.id, outcome, event.data.reference, amount)
    }

    private fun invalid(): Nothing = throw DomainException(ErrorKind.UNAUTHENTICATED, "WEBHOOK_SIGNATURE_INVALID", "The webhook signature is invalid.")

    @Serializable
    internal data class SandboxEvent(val id: String, val type: String, val data: SandboxEventData)

    @Serializable
    internal data class SandboxEventData(val reference: String? = null, val amountMinor: Long? = null, val currency: String? = null)

    companion object {
        const val SIGNATURE_HEADER = "Gavel-Signature"
        private val json = Json { ignoreUnknownKeys = true }

        fun sign(secret: String, timestamp: Long, body: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            return mac.doFinal("$timestamp.$body".toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
