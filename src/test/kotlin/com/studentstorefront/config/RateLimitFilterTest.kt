package com.studentstorefront.config

import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RateLimitFilterTest {

    private lateinit var filter: RateLimitFilter

    @BeforeEach
    fun setup() {
        // fresh filter = fresh Caffeine cache = fresh buckets for every test
        filter = RateLimitFilter()
    }

    // fires a POST and returns the response; each call uses its own relaxed chain mock
    private fun post(path: String, ip: String = "10.0.0.1"): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", path).apply { remoteAddr = ip }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, mockk(relaxed = true))
        return response
    }

    // ── per-endpoint limits ────────────────────────────────────────────────────

    @Test
    fun `login allows 5 requests then blocks on the sixth`() {
        repeat(5) { assertEquals(200, post("/api/auth/login").status) }
        assertEquals(429, post("/api/auth/login").status)
    }

    @Test
    fun `forgot-password allows 3 requests then blocks on the fourth`() {
        repeat(3) { assertEquals(200, post("/api/auth/forgot-password").status) }
        assertEquals(429, post("/api/auth/forgot-password").status)
    }

    @Test
    fun `resend-verification allows 3 requests then blocks on the fourth`() {
        repeat(3) { assertEquals(200, post("/api/auth/resend-verification").status) }
        assertEquals(429, post("/api/auth/resend-verification").status)
    }

    // ── bucket isolation ───────────────────────────────────────────────────────

    @Test
    fun `different IPs have independent quotas`() {
        repeat(3) { post("/api/auth/forgot-password", ip = "1.2.3.4") }
        // exhausted for 1.2.3.4 but a different IP still has its full quota
        assertEquals(200, post("/api/auth/forgot-password", ip = "9.9.9.9").status)
    }

    @Test
    fun `different endpoints have independent quotas`() {
        repeat(3) { post("/api/auth/forgot-password") }
        // forgot-password bucket exhausted, but login bucket is untouched
        assertEquals(200, post("/api/auth/login").status)
    }

    // ── 429 response shape ─────────────────────────────────────────────────────

    @Test
    fun `blocked response has correct status, content type, and message field`() {
        repeat(5) { post("/api/auth/login") }
        val response = post("/api/auth/login")
        assertEquals(429, response.status)
        assertTrue(response.contentType?.startsWith("application/json") == true)
        assertTrue(response.contentAsString.contains("\"message\""))
    }

    // ── IP extraction ──────────────────────────────────────────────────────────

    private fun forgotPassword(filter: RateLimitFilter, remote: String, forwardedFor: String?): Int {
        val request = MockHttpServletRequest("POST", "/api/auth/forgot-password").apply {
            remoteAddr = remote
            forwardedFor?.let { addHeader("X-Forwarded-For", it) }
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, mockk(relaxed = true))
        return response.status
    }

    @Test
    fun `X-Forwarded-For is ignored when the peer is not a trusted proxy`() {
        // A client talking to us directly can't escape its quota by forging the header.
        repeat(3) { forgotPassword(filter, "198.51.100.7", "203.0.113.$it") }
        assertEquals(429, forgotPassword(filter, "198.51.100.7", "203.0.113.99"))
    }

    @Test
    fun `behind a trusted proxy the right-most untrusted hop is the client`() {
        val proxied = RateLimitFilter(trustedProxyAddresses = "127.0.0.1")
        // The left entries are client-supplied; only the hop the proxy appended counts.
        repeat(3) { forgotPassword(proxied, "127.0.0.1", "10.9.9.$it, 203.0.113.42") }
        assertEquals(429, forgotPassword(proxied, "127.0.0.1", "10.9.9.99, 203.0.113.42"))
        assertEquals(200, forgotPassword(proxied, "127.0.0.1", "203.0.113.43"))
    }

    // ── bypass conditions ──────────────────────────────────────────────────────

    @Test
    fun `GET requests to rate-limited paths are never blocked`() {
        repeat(10) {
            val request = MockHttpServletRequest("GET", "/api/auth/login").apply { remoteAddr = "1.2.3.4" }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, mockk(relaxed = true))
            assertEquals(200, response.status)
        }
    }

    @Test
    fun `listing writes are limited per account token`() {
        fun createPost(token: String): Int {
            val request = MockHttpServletRequest("POST", "/api/posts").apply {
                remoteAddr = "10.0.0.1"
                addHeader("Authorization", "Bearer $token")
            }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, mockk(relaxed = true))
            return response.status
        }
        repeat(20) { assertEquals(200, createPost("token-a")) }
        assertEquals(429, createPost("token-a"))
        // Another account behind the same address keeps its own quota.
        assertEquals(200, createPost("token-b"))
    }

    // ── filter chain delegation ────────────────────────────────────────────────

    @Test
    fun `allowed request is forwarded to the filter chain`() {
        val chain: FilterChain = mockk(relaxed = true)
        val request = MockHttpServletRequest("POST", "/api/auth/login").apply { remoteAddr = "1.2.3.4" }
        filter.doFilter(request, MockHttpServletResponse(), chain)
        verify(exactly = 1) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `blocked request is not forwarded to the filter chain`() {
        repeat(5) { post("/api/auth/login") }

        val chain: FilterChain = mockk(relaxed = true)
        val request = MockHttpServletRequest("POST", "/api/auth/login").apply { remoteAddr = "10.0.0.1" }
        filter.doFilter(request, MockHttpServletResponse(), chain)

        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }
}
