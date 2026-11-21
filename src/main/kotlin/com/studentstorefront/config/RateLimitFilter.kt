package com.studentstorefront.config

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.Clock

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RateLimitFilter(
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${app.trusted-proxy-addresses:}") private val trustedProxyAddresses: String = ""
) : OncePerRequestFilter() {

    // requests per minute allowed per IP for each protected path
    private val limits = mapOf(
        "/api/auth/register" to 3L,
        "/api/auth/login" to 5L,
        "/api/auth/forgot-password" to 3L,
        "/api/auth/reset-password" to 5L,
        "/api/auth/resend-verification" to 3L,
        "/api/auth/verify-email" to 5L,
        "/api/auth/whatsapp/session" to 10L,
        "/api/auth/whatsapp/claim" to 15L,
        "/api/auth/whatsapp/confirm" to 20L
    )

    // Writes elsewhere in the API, per signed-in session (or per IP when
    // anonymous), so one account can't flood listings, uploads or reviews.
    private val writeLimits = listOf(
        "/api/posts/bot" to 120L,
        "/api/posts" to 20L,
        "/api/reviews" to 10L,
        "/api/sales" to 20L,
        "/api/users" to 10L,
        "/api/favourites" to 60L
    )

    private val trustedProxies: Set<String> by lazy {
        trustedProxyAddresses.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
    }

    @Autowired(required = false)
    private var jdbcTemplate: JdbcTemplate? = null

    // keyed by "$ip:$path"; evict entries idle for 1 hour to prevent unbounded growth
    private val buckets = Caffeine.newBuilder()
        .expireAfterAccess(1, TimeUnit.HOURS)
        .maximumSize(100_000)
        .build<String, Bucket>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = request.requestURI.removeSuffix("/").ifBlank { "/" }
        val isSessionPoll = request.method == "GET" &&
            path.startsWith("/api/auth/whatsapp/session/")
        val limit = limits[path] ?: if (isSessionPoll) 60L else null

        val rule = when {
            limit != null && (request.method == "POST" || isSessionPoll) -> "${resolveClientIp(request)}:$path" to limit
            limit == null && request.method in WRITE_METHODS -> writeLimits
                .firstOrNull { (prefix, _) -> path == prefix || path.startsWith("$prefix/") }
                ?.let { (prefix, perMinute) -> "${writeIdentity(request)}:write:$prefix" to perMinute }
            else -> null
        }

        if (rule != null) {
            val (key, perMinute) = rule
            val allowed = consumeSharedBudget(key, perMinute)
                ?: buckets.get(key) { buildBucket(it, perMinute) }!!.tryConsume(1)

            if (!allowed) {
                com.studentstorefront.exception.ErrorBody.write(
                    response, HttpStatus.TOO_MANY_REQUESTS.value(), "RATE_LIMITED", "Too many requests. Please try again later."
                )
                return
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun consumeSharedBudget(key: String, limit: Long): Boolean? {
        val jdbc = jdbcTemplate ?: return null
        val windowStart = currentWindowStart()
        val sql = """
            INSERT INTO api_rate_limit_windows(rate_key, window_start, request_count)
            VALUES (?, ?, 1)
            ON CONFLICT (rate_key) DO UPDATE
            SET window_start = EXCLUDED.window_start,
                request_count = CASE
                    WHEN api_rate_limit_windows.window_start <> EXCLUDED.window_start THEN 1
                    ELSE api_rate_limit_windows.request_count + 1
                END
            WHERE api_rate_limit_windows.window_start <> EXCLUDED.window_start
               OR api_rate_limit_windows.request_count < ?
        """.trimIndent()
        return runCatching {
            jdbc.update(sql, key, Timestamp.valueOf(windowStart), limit) == 1
        }.getOrNull()
    }

    private fun currentWindowStart(): LocalDateTime {
        val epochMinute = Instant.now(clock).epochSecond / 60 * 60
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochMinute), ZoneOffset.UTC)
    }

    private fun buildBucket(@Suppress("UNUSED_PARAMETER") key: String, requestsPerMinute: Long): Bucket =
        Bucket.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(requestsPerMinute)
                    .refillIntervally(requestsPerMinute, Duration.ofMinutes(1))
                    .build()
            )
            .build()

    /**
     * The client address as seen by the closest proxy we trust. X-Forwarded-For
     * is read right to left and trusted hops are skipped, because anything to
     * the left of the first untrusted hop is client-controlled (BE-07).
     */
    private fun resolveClientIp(request: HttpServletRequest): String {
        val remote = request.remoteAddr
        if (remote !in trustedProxies) return remote
        val hops = request.getHeaders("X-Forwarded-For").toList()
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
        return hops.lastOrNull { it !in trustedProxies } ?: remote
    }

    /** Signed-in writes are budgeted per session token, anonymous ones per IP. */
    private fun writeIdentity(request: HttpServletRequest): String {
        val bearer = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
            ?.takeIf { it.isNotEmpty() }
        return if (bearer != null) "token:" + sha256(bearer) else "ip:" + resolveClientIp(request)
    }

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private companion object {
        val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}
