package com.gavel.onboarding.web

import com.gavel.kernel.audit.Actor
import com.gavel.kernel.auth.TokenPrincipal
import com.gavel.kernel.web.ApiException
import com.gavel.onboarding.service.AccountService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.security.MessageDigest
import java.util.UUID

const val BOOTSTRAP_RATE_LIMITER = "onboarding-bootstrap"

/**
 * The four application endpoints of M2 plus the operator endpoints (MILESTONES.md).
 * Mount under a route where `installWebKernel` and the `keycloak` authentication are installed.
 */
fun Route.userOnboardingRoutes(service: AccountService, internalApiToken: String?) {
    rateLimit(RateLimitName(BOOTSTRAP_RATE_LIMITER)) {
        authenticate {
            get("/api/v1/me") {
                call.respond(AccountResponse.from(service.bootstrap(call.tokenPrincipal(), call.correlationId())))
            }
        }
    }

    authenticate {
        route("/api/v1/profile/{userId}") {
            get {
                call.respond(AccountResponse.from(service.readOwn(call.tokenPrincipal(), call.userIdParam())))
            }
            put {
                val (command, errors) = call.receive<UpdateProfileRequest>().toCommand()
                if (command == null) throw ApiException.validation(errors)
                call.respond(AccountResponse.from(service.updateOwn(call.tokenPrincipal(), call.userIdParam(), command, call.correlationId())))
            }
            post("enable-seller") {
                call.respond(AccountResponse.from(service.enableSeller(call.tokenPrincipal(), call.userIdParam(), call.correlationId())))
            }
        }
    }

    route("/internal/accounts/{userId}") {
        post("suspend") {
            call.requireInternalToken(internalApiToken)
            val reason = call.receive<SuspendRequest>().reason.trim()
            if (reason.isEmpty()) throw ApiException.validation(listOf(com.gavel.kernel.web.ErrorDetail(field = "reason", code = "REQUIRED")))
            call.respond(AccountResponse.from(service.suspend(call.userIdParam(), reason, Actor.System, call.correlationId())))
        }
        post("reactivate") {
            call.requireInternalToken(internalApiToken)
            call.respond(AccountResponse.from(service.reactivate(call.userIdParam(), Actor.System, call.correlationId())))
        }
    }
}

private fun ApplicationCall.tokenPrincipal(): TokenPrincipal =
    principal<TokenPrincipal>() ?: throw ApiException.unauthorized("TOKEN_MISSING", "Bearer token required")

private fun ApplicationCall.userIdParam(): String = parameters["userId"] ?: throw ApiException(HttpStatusCode.NotFound, "NOT_FOUND", "No such resource")

private fun ApplicationCall.correlationId(): UUID? = callId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** Operator endpoints: shared secret in `X-Internal-Token`, compared in constant time; disabled when unset. */
private fun ApplicationCall.requireInternalToken(expected: String?) {
    if (expected == null) throw ApiException.forbidden("INTERNAL_API_DISABLED", "Operator endpoints are not configured")
    val presented = request.headers["X-Internal-Token"] ?: throw ApiException.unauthorized("TOKEN_MISSING", "X-Internal-Token required")
    if (!MessageDigest.isEqual(presented.toByteArray(), expected.toByteArray())) throw ApiException.forbidden("FORBIDDEN", "Invalid operator token")
}
