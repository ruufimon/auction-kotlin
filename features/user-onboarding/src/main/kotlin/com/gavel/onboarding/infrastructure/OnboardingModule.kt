package com.gavel.onboarding.infrastructure

import com.auth0.jwt.JWTVerifier
import com.gavel.kernel.AuditLog
import com.gavel.kernel.EventPublisher
import com.gavel.kernel.Transactor
import com.gavel.kernel.UserId
import com.gavel.onboarding.api.AccountStatus
import com.gavel.onboarding.api.Participant
import com.gavel.onboarding.api.Participants
import com.gavel.onboarding.api.PublicProfile
import com.gavel.onboarding.api.ShippingContact
import com.gavel.onboarding.application.EmailSender
import com.gavel.onboarding.application.OnboardingService
import com.gavel.onboarding.application.OnboardingSettings
import com.gavel.onboarding.application.PasswordHasher
import com.gavel.onboarding.application.Session
import com.gavel.web.currentUserId
import com.gavel.web.expectedVersion
import com.gavel.web.pathParam
import com.gavel.web.receiveJson
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration

data class RefreshCookieSettings(
    val name: String = "gavel_refresh",
    /** Must be true in production; false only for plain-HTTP local development. */
    val secure: Boolean = true,
    /** "None" lets a web app on another origin send the cookie; it requires [secure]. */
    val sameSite: String = "None",
    val path: String = "/api/v1/auth",
)

data class OnboardingConfig(
    val jwt: JwtSettings,
    val cookie: RefreshCookieSettings = RefreshCookieSettings(),
    val settings: OnboardingSettings = OnboardingSettings(),
)

/** Wires user onboarding; the app mounts its routes and uses [participants] across modules. */
class OnboardingModule(
    private val tx: Transactor,
    audit: AuditLog,
    events: EventPublisher,
    private val clock: Clock,
    private val config: OnboardingConfig,
    emailSender: EmailSender,
    hasher: PasswordHasher = Argon2PasswordHasher(),
) {
    private val repository = ExposedAccountRepository(clock)
    private val tokens = JwtAccessTokens(config.jwt, clock)
    val service = OnboardingService(tx, repository, hasher, tokens, emailSender, audit, events, clock, config.settings)

    val jwtVerifier: JWTVerifier get() = tokens.verifier

    /** Called by the bearer-token provider so suspended accounts lose access immediately. */
    suspend fun isActive(userId: UserId): Boolean =
        tx.inTransaction { repository.findById(userId)?.status == AccountStatus.ACTIVE }

    val participants: Participants = object : Participants {
        override fun find(id: UserId): Participant? = repository.findById(id)?.let {
            Participant(it.id, it.status, it.emailVerified, it.sellerEligible, it.displayName)
        }

        override fun publicProfiles(ids: Collection<UserId>): Map<UserId, PublicProfile> =
            repository.findByIds(ids.distinct()).associate { it.id to PublicProfile(it.id, it.displayName) }

        override fun shippingContact(id: UserId): ShippingContact? = repository.findById(id)?.let { account ->
            account.shippingAddress?.let {
                ShippingContact(account.displayName, account.phone, it.line1, it.line2, it.city, it.region, it.postalCode, it.country)
            }
        }
    }

    /** Unauthenticated /auth endpoints; the app wraps them in rate limiting. */
    val authRoutes: Route.() -> Unit = {
        route("/auth") {
            post("/register") {
                val body = call.receiveJson<RegisterRequest>()
                val account = service.register(
                    com.gavel.onboarding.application.RegisterCommand(body.email, body.password, body.displayName, body.phone, body.acceptedTerms),
                )
                call.respond(HttpStatusCode.Created, account.toDto())
            }
            post("/verify-email") {
                val session = service.verifyEmail(call.receiveJson<VerifyEmailRequest>().token)
                call.respondSession(session)
            }
            post("/verify-email/resend") {
                service.resendVerification(call.receiveJson<ResendVerificationRequest>().email)
                call.respond(HttpStatusCode.Accepted, Accepted())
            }
            post("/sign-in") {
                val body = call.receiveJson<SignInRequest>()
                call.respondSession(service.signIn(body.email, body.password))
            }
            post("/refresh") {
                val token = call.request.cookies[config.cookie.name]
                    ?: throw com.gavel.kernel.DomainException(com.gavel.kernel.ErrorKind.UNAUTHENTICATED, "REFRESH_TOKEN_INVALID", "Your session has ended. Sign in again.")
                try {
                    call.respondSession(service.refresh(token))
                } catch (e: com.gavel.kernel.DomainException) {
                    call.clearRefreshCookie()
                    throw e
                }
            }
            post("/sign-out") {
                service.signOut(call.request.cookies[config.cookie.name])
                call.clearRefreshCookie()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    /** Endpoints for the signed-in user; the app wraps them in bearer authentication. */
    val accountRoutes: Route.() -> Unit = {
        route("/me") {
            get { call.respond(service.me(call.currentUserId()).toDto()) }
            patch {
                val body = call.receiveJson<UpdateProfileRequest>()
                val update = com.gavel.onboarding.application.ProfileUpdate(
                    body.displayName, body.phone, body.shippingAddress?.toDomain(), body.preferences?.toDomain(),
                )
                call.respond(service.updateProfile(call.currentUserId(), update, call.expectedVersion()).toDto())
            }
            put("/seller-profile") {
                val body = call.receiveJson<SellerProfileDto>()
                call.respond(service.updateSellerProfile(call.currentUserId(), body.toDomain(), call.expectedVersion()).toDto())
            }
        }
    }

    /** Operator endpoints; the app wraps them in operator authentication. */
    val adminRoutes: Route.() -> Unit = {
        route("/accounts/{userId}") {
            post("/suspend") {
                val body = call.receiveJson<SuspendRequest>()
                val account = service.suspend(UserId.parse(call.pathParam("userId")), body.operator, body.reason)
                call.respond(account.toDto())
            }
            post("/reactivate") {
                val body = call.receiveJson<ReactivateRequest>()
                call.respond(service.reactivate(UserId.parse(call.pathParam("userId")), body.operator).toDto())
            }
        }
    }

    private suspend fun ApplicationCall.respondSession(session: Session) {
        val maxAge = Duration.between(clock.instant(), session.refreshExpiresAt).seconds.coerceAtLeast(0)
        response.cookies.append(refreshCookie(session.refreshToken, maxAge.toInt()))
        respond(session.toDto())
    }

    private fun ApplicationCall.clearRefreshCookie() {
        response.cookies.append(refreshCookie("", 0))
    }

    private fun refreshCookie(value: String, maxAge: Int) = Cookie(
        name = config.cookie.name,
        value = value,
        maxAge = maxAge,
        path = config.cookie.path,
        secure = config.cookie.secure,
        httpOnly = true,
        extensions = mapOf("SameSite" to config.cookie.sameSite),
    )
}

@Serializable
internal data class Accepted(val status: String = "ACCEPTED")

@Serializable
internal data class SuspendRequest(val operator: String, val reason: String? = null)

@Serializable
internal data class ReactivateRequest(val operator: String)
