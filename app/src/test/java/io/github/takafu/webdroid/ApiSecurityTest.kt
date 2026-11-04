package io.github.takafu.webdroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSecurityTest {
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `accepts matching bearer token`() {
        assertTrue(ApiSecurity.isAuthorized("Bearer $token", token))
    }

    @Test
    fun `bearer scheme is case insensitive`() {
        assertTrue(ApiSecurity.isAuthorized("bEaReR $token", token))
    }

    @Test
    fun `rejects missing or malformed authorization`() {
        assertFalse(ApiSecurity.isAuthorized(null, token))
        assertFalse(ApiSecurity.isAuthorized("", token))
        assertFalse(ApiSecurity.isAuthorized("Basic $token", token))
        assertFalse(ApiSecurity.isAuthorized("Bearer", token))
        assertFalse(ApiSecurity.isAuthorized("Bearer ", token))
    }

    @Test
    fun `rejects wrong and oversized tokens`() {
        assertFalse(ApiSecurity.isAuthorized("Bearer wrong", token))
        assertFalse(ApiSecurity.isAuthorized("Bearer ${"x".repeat(513)}", token))
    }
}
