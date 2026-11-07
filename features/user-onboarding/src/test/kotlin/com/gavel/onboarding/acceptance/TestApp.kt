package com.gavel.onboarding.acceptance

import com.gavel.kernel.auth.keycloak
import com.gavel.kernel.web.installWebKernel
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication

/**
 * The application as the acceptance tests see it: kernel plugins, token validation
 * trusting the scenario's identity provider, and the onboarding feature.
 */
fun Application.onboardingTestModule(world: World) {
    installWebKernel()
    install(Authentication) {
        keycloak {
            issuer = world.idp.issuer
            audience = world.idp.audience
            jwkProvider = world.idp.jwkProvider
            clock = world.clock
        }
    }
    // Feature routes are mounted here once they exist.
}
