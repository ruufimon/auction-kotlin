package com.gavel.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtTokenServiceTest {
    
    private val tokenService = JwtTokenService()

    @Test
    fun testIssueAccessToken() {
        val userId = "user-123"
        val email = "user@example.com"
        
        val token = tokenService.issueAccessToken(userId, email)
        
        assertNotNull(token)
        assertTrue(token.isNotBlank())
    }

    @Test
    fun testValidateAccessTokenSucceeds() {
        val userId = "user-123"
        val email = "user@example.com"
        
        val token = tokenService.issueAccessToken(userId, email)
        val claims = tokenService.validateAccessToken(token)
        
        assertNotNull(claims)
        assertTrue(claims.userId == userId)
        assertTrue(claims.email == email)
    }

    @Test
    fun testValidateAccessTokenWithInvalidTokenFails() {
        val invalidToken = "invalid-token-xyz"
        val claims = tokenService.validateAccessToken(invalidToken)
        
        assertNull(claims)
    }

    @Test
    fun testTokenHasExpirationTime() {
        val userId = "user-123"
        val email = "user@example.com"
        
        val token = tokenService.issueAccessToken(userId, email)
        val claims = tokenService.validateAccessToken(token)
        
        assertNotNull(claims)
        assertTrue(claims.expiresAt.isAfter(claims.issuedAt))
    }
}
