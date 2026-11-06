package com.gavel.app

import com.gavel.onboarding.infrastructure.DevelopmentLinkEmailSender
import com.gavel.onboarding.infrastructure.LoggingEmailSender
import com.gavel.web.AUTH_USER
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

fun main() {
    val config = AppConfig.fromEnvironment()
    val dataSource = dataSource(config.database)
    migrate(dataSource)
    val emailSender = if (config.devEmailLinks) DevelopmentLinkEmailSender() else LoggingEmailSender()
    val gavel = Gavel(config, dataSource, emailSender = emailSender)
    if (config.seedDemoData) DemoSeeder(gavel).seed()

    embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
        gavelModule(gavel)
        monitor.subscribe(ApplicationStarted) { gavel.backgroundJobs.start() }
        monitor.subscribe(ApplicationStopping) {
            gavel.backgroundJobs.stop()
            dataSource.close()
        }
    }.start(wait = true)
}

fun Application.gavelModule(gavel: Gavel, rateLimits: RateLimits = RateLimits()) {
    installHttp(gavel, rateLimits)
    gavel.registerGauges()
    routing {
        get("/") { call.respondText("Hello, World!") }
        get("/health/live") { call.respondText("OK") }
        get("/health/ready") {
            val ready = withContext(Dispatchers.IO) { isDatabaseReachable(gavel.dataSource) }
            if (ready) call.respondText("READY") else call.respondText("NOT READY", status = HttpStatusCode.ServiceUnavailable)
        }
        gavel.config.metricsToken?.let { token ->
            get("/metrics") {
                val auth = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                if (auth == null || !com.gavel.kernel.Secrets.constantTimeEquals(auth, token)) {
                    call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                } else {
                    call.respondText(gavel.meterRegistry.scrape(), ContentType.parse("text/plain; version=0.0.4"))
                }
            }
        }
        get("/api/v1/openapi.yaml") {
            val spec = Gavel::class.java.getResource("/openapi/documentation.yaml")!!.readText()
            call.respondText(spec, ContentType.parse("application/yaml"))
        }
        route("/api/v1") { apiRoutes(gavel) }
        if (gavel.config.adminToken != null) {
            authenticate(AUTH_ADMIN) { route("/admin/v1") { gavel.onboarding.adminRoutes(this) } }
        } else {
            LoggerFactory.getLogger("com.gavel.app").info("ADMIN_TOKEN is not set; operator endpoints are disabled")
        }
    }
}

private fun Route.apiRoutes(gavel: Gavel) {
    rateLimit(AUTH_RATE_LIMIT) { gavel.onboarding.authRoutes(this) }
    rateLimit(WEBHOOK_RATE_LIMIT) { gavel.settlement.webhookRoutes(this) }
    authenticate(AUTH_USER, optional = true) {
        gavel.catalog.publicRoutes(this)
        gavel.bidding.publicRoutes(this)
    }
    authenticate(AUTH_USER) {
        gavel.onboarding.accountRoutes(this)
        gavel.catalog.sellerRoutes(this)
        gavel.bidding.userRoutes(this)
        gavel.settlement.userRoutes(this)
    }
}
