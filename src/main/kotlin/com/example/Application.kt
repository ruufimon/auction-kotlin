package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, host = "0.0.0.0", port = port, module = { module() })
        .start(wait = true)
}

/**
 * @param readinessCheck reports whether required infrastructure (e.g. the
 * database) is reachable. Readiness fails while it returns false.
 */
fun Application.module(readinessCheck: suspend () -> Boolean = { true }) {
    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/health/live") {
            call.respondText("OK")
        }
        get("/health/ready") {
            if (readinessCheck()) {
                call.respondText("READY")
            } else {
                call.respondText("NOT READY", status = HttpStatusCode.ServiceUnavailable)
            }
        }
    }
}
