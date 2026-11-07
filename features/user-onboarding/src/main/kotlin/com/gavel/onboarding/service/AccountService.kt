package com.gavel.onboarding.service

import com.gavel.kernel.audit.Actor
import com.gavel.kernel.audit.AuditLog
import com.gavel.kernel.auth.TokenPrincipal
import com.gavel.kernel.web.ApiException
import com.gavel.kernel.web.ErrorDetail
import com.gavel.onboarding.domain.Account
import com.gavel.onboarding.domain.AccountChange
import com.gavel.onboarding.domain.AccountError
import com.gavel.onboarding.domain.CreditPolicy
import com.gavel.onboarding.domain.Email
import com.gavel.onboarding.domain.EligibilityOutcome
import com.gavel.onboarding.domain.IdentityClaims
import com.gavel.onboarding.domain.IdpSubject
import com.gavel.onboarding.domain.ProfilePatch
import com.gavel.onboarding.domain.SellerRequirements
import com.gavel.onboarding.domain.UserId
import com.gavel.onboarding.persistence.AccountRepository
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/** What a profile update may carry; assessment inputs are already validated numbers here. */
data class UpdateProfileCommand(
    val expectedVersion: Int?,
    val patch: ProfilePatch,
    val assessment: AssessmentCommand?,
)

data class AssessmentCommand(val age: Int, val annualIncomeMinor: Long, val netWealthMinor: Long) {
    override fun toString(): String = "AssessmentCommand(redacted)"
}

/**
 * Application service for accounts: transactions, outbox publication, audit and
 * metrics around the [Account] aggregate. Authorization decisions come from the
 * aggregate's state, never from token validity alone (requirement §6.1).
 */
class AccountService(
    private val database: Database,
    private val repository: AccountRepository,
    private val events: AccountEventPublisher,
    private val audit: AuditLog,
    private val clock: Clock,
    private val policy: () -> CreditPolicy,
    private val sellerRequirements: () -> SellerRequirements,
    private val identityProvider: IdentityProviderAdmin,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(AccountService::class.java)

    /** `GET /api/v1/me`: create on first call, link by verified email, then apply the token's claims (USR-013). */
    suspend fun bootstrap(principal: TokenPrincipal, correlationId: UUID?): Account {
        val claims = principal.toClaims()
        val existing = tx { repository.findBySubject(claims.subject) }
        val account = existing ?: createOrLink(claims, correlationId)
        return tx {
            val current = if (existing != null) existing else repository.findBySubject(claims.subject) ?: account
            val change = current.syncClaims(claims, clock.instant())
            if (!change.changed && change.account == current) return@tx current
            if (!repository.update(change.account)) {
                // Lost a race with a concurrent request; its result is equally valid.
                return@tx repository.findBySubject(claims.subject) ?: current
            }
            events.publishAll(change.account.id, change.events, correlationId)
            if (change.account.state != current.state) {
                audit.record(Actor.User(current.id), "email.verified", AGGREGATE, current.id, mapOf("from" to current.state.name, "to" to change.account.state.name), correlationId)
                count("transitioned")
            }
            change.account
        }
    }

    private suspend fun createOrLink(claims: IdentityClaims, correlationId: UUID?): Account {
        val linked = if (claims.emailVerified) tx { repository.findByVerifiedEmail(claims.email) } else null
        if (linked != null) {
            val attached = linked.linkSubject(claims.subject, clock.instant())
            val written = tx {
                if (repository.update(attached)) {
                    audit.record(Actor.User(linked.id), "account.identity-linked", AGGREGATE, linked.id, mapOf("email" to claims.email.value), correlationId)
                    true
                } else false
            }
            count("linked")
            if (written) return attached
            return tx { repository.findBySubject(claims.subject) } ?: createOrLink(claims, correlationId)
        }
        val change = Account.bootstrap(claims, clock.instant())
        val inserted = try {
            tx {
                repository.insert(change.account)
                events.publishAll(change.account.id, change.events, correlationId)
                audit.record(Actor.User(change.account.id), "account.created", AGGREGATE, change.account.id, mapOf("state" to change.account.state.name), correlationId)
                true
            }
        } catch (e: ExposedSQLException) {
            if (e.sqlState != UNIQUE_VIOLATION) throw e
            false
        }
        if (inserted) {
            count("created")
            return change.account
        }
        // A concurrent first call inserted the row; one account per subject (USR-013).
        count("raced")
        return tx { repository.findBySubject(claims.subject) } ?: error("account vanished after unique violation")
    }

    suspend fun readOwn(principal: TokenPrincipal, userId: String): Account = tx { loadOwn(principal, userId) }

    /**
     * `PUT /api/v1/profile/{userId}`. Profile changes and an optional assessment are
     * committed together; a failed assessment is still persisted and then reported as 422.
     */
    suspend fun updateOwn(principal: TokenPrincipal, userId: String, command: UpdateProfileCommand, correlationId: UUID?): Account {
        val result = tx {
            val account = loadOwn(principal, userId)
            if (command.expectedVersion != null && command.expectedVersion != account.version) {
                throw ApiException.conflict("VERSION_CONFLICT", "The profile changed since version ${command.expectedVersion}; reload and retry",
                    listOf(ErrorDetail(field = "version", code = "STALE", value = account.version.toString())))
            }
            val now = clock.instant()
            var change: AccountChange = try { account.updateProfile(command.patch, now) } catch (e: AccountError) { throw e.toApiException() }
            var assessmentOutcome: EligibilityOutcome? = null
            if (command.assessment != null) {
                val a = command.assessment
                val assessed = try { change.account.assess(a.age, a.annualIncomeMinor, a.netWealthMinor, policy(), now) } catch (e: AccountError) { throw e.toApiException() }
                assessmentOutcome = assessed.account.assessment?.outcome
                change = AccountChange(assessed.account, change.events + assessed.events)
            }
            if (!change.changed) return@tx account to null
            if (!repository.update(change.account)) {
                throw ApiException.conflict("VERSION_CONFLICT", "The profile was modified concurrently; reload and retry")
            }
            events.publishAll(account.id, change.events, correlationId)
            if (change.events.any { it.eventType.endsWith("profile-updated") }) {
                audit.record(Actor.User(account.id), "profile.updated", AGGREGATE, account.id, emptyMap(), correlationId)
            }
            if (assessmentOutcome != null) {
                val stored = change.account.assessment!!
                // Outcome and policy only: raw inputs never reach logs or audit (USR-012).
                audit.record(Actor.User(account.id), "credit-eligibility.assessed", AGGREGATE, account.id,
                    mapOf("outcome" to stored.outcome.name, "reason" to stored.reason, "policyVersion" to stored.policyVersion), correlationId)
                log.info("Credit eligibility assessed outcome={} policyVersion={} correlationId={}", stored.outcome, stored.policyVersion, correlationId)
                meterRegistry.counter("onboarding.assessment.total", "outcome", stored.outcome.name).increment()
            }
            change.account to assessmentOutcome
        }
        val (account, outcome) = result
        if (outcome == EligibilityOutcome.NOT_ELIGIBLE) {
            throw AccountError.CreditEligibilityNotMet(account.assessment!!.reason).toApiException()
        }
        return account
    }

    suspend fun enableSeller(principal: TokenPrincipal, userId: String, correlationId: UUID?): Account = tx {
        val account = loadOwn(principal, userId)
        val change = try { account.enableSeller(sellerRequirements(), clock.instant()) } catch (e: AccountError) { throw e.toApiException() }
        if (!repository.update(change.account)) throw ApiException.conflict("VERSION_CONFLICT", "The profile was modified concurrently; retry")
        events.publishAll(account.id, change.events, correlationId)
        audit.record(Actor.User(account.id), "seller.enabled", AGGREGATE, account.id, emptyMap(), correlationId)
        meterRegistry.counter("onboarding.seller_enabled.total").increment()
        change.account
    }

    /** Operator action (USR-010): application state first, provider second; tokens are rejected immediately. */
    suspend fun suspend(userId: String, reason: String, actor: Actor, correlationId: UUID?): Account {
        val suspended = tx {
            val account = findByPublicId(userId)
            val change = try { account.suspend(reason, clock.instant()) } catch (e: AccountError) { throw e.toApiException() }
            if (!repository.update(change.account)) throw ApiException.conflict("VERSION_CONFLICT", "The account was modified concurrently; retry")
            events.publishAll(account.id, change.events, correlationId)
            audit.record(actor, "account.suspended", AGGREGATE, account.id, mapOf("reason" to reason), correlationId)
            change.account
        }
        syncProvider(suspended, correlationId) { identityProvider.disableUser(suspended.subject.value) }
        return suspended
    }

    suspend fun reactivate(userId: String, actor: Actor, correlationId: UUID?): Account {
        val reactivated = tx {
            val account = findByPublicId(userId)
            val change = try { account.reactivate(clock.instant()) } catch (e: AccountError) { throw e.toApiException() }
            if (!repository.update(change.account)) throw ApiException.conflict("VERSION_CONFLICT", "The account was modified concurrently; retry")
            events.publishAll(account.id, change.events, correlationId)
            audit.record(actor, "account.reactivated", AGGREGATE, account.id, emptyMap(), correlationId)
            change.account
        }
        syncProvider(reactivated, correlationId) { identityProvider.enableUser(reactivated.subject.value) }
        return reactivated
    }

    private suspend fun syncProvider(account: Account, correlationId: UUID?, call: suspend () -> Unit) {
        try {
            call()
        } catch (e: Exception) {
            // The application state already rejects tokens; the outbox handler retries the provider call.
            log.warn("Identity provider sync failed for account {}; will retry through the outbox", account.userId.value, e)
            tx { audit.record(Actor.System, "identity-provider.sync-failed", AGGREGATE, account.id, mapOf("error" to e.javaClass.simpleName), correlationId) }
        }
    }

    private fun loadOwn(principal: TokenPrincipal, userId: String): Account {
        val account = repository.findBySubject(IdpSubject(principal.subject))
            ?: throw ApiException.forbidden("ACCOUNT_NOT_BOOTSTRAPPED", "Call GET /api/v1/me first")
        // Another user's id and a non-existent id are indistinguishable on purpose.
        if (account.userId.value != userId) throw ApiException.forbidden("FORBIDDEN", "Not your profile")
        return account
    }

    private fun findByPublicId(userId: String): Account {
        val id = UserId.parseOrNull(userId) ?: throw ApiException(io.ktor.http.HttpStatusCode.NotFound, "NOT_FOUND", "No such account")
        return repository.findByUserId(id) ?: throw ApiException(io.ktor.http.HttpStatusCode.NotFound, "NOT_FOUND", "No such account")
    }

    private fun TokenPrincipal.toClaims(): IdentityClaims {
        val email = Email.ofOrNull(this.email)
            ?: throw ApiException.forbidden("TOKEN_MISSING_CLAIMS", "The access token carries no usable email claim")
        return IdentityClaims(IdpSubject(subject), email, emailVerified, displayName?.takeIf { it.isNotBlank() }?.take(Account.DISPLAY_NAME_MAX))
    }

    private fun count(outcome: String) = meterRegistry.counter("onboarding.bootstrap.total", "outcome", outcome).increment()

    private suspend fun <T> tx(block: () -> T): T = newSuspendedTransaction(Dispatchers.IO, database) { block() }

    private companion object {
        const val AGGREGATE = "account"
        const val UNIQUE_VIOLATION = "23505"
    }
}
