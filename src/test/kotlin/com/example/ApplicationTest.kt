package com.example

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `root endpoint returns hello world`() = testApplication {
        application { module() }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, World!", response.bodyAsText())
    }
}
