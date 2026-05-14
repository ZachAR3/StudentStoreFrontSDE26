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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RateLimitFilter : OncePerRequestFilter() {

    // requests per minute allowed per IP for each protected path
    private val limits = mapOf(
        "/api/auth/login" to 5L,
        "/api/auth/forgot-password" to 3L,
        "/api/auth/resend-verification" to 3L,
        "/api/auth/verify-email" to 5L,
        "/api/auth/whatsapp/session" to 10L,
        "/api/auth/whatsapp/claim" to 15L,
        "/api/auth/whatsapp/confirm" to 20L
    )

    // keyed by "$ip:$path"; evict entries idle for 1 hour to prevent unbounded growth
    private val buckets = Caffeine.newBuilder()
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build<String, Bucket>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val limit = limits[request.requestURI]

        if (limit != null && request.method == "POST") {
            val ip = resolveClientIp(request)
            val bucket = buckets.get("$ip:${request.requestURI}") { buildBucket(it, limit) }!!

            if (!bucket.tryConsume(1)) {
                response.status = HttpStatus.TOO_MANY_REQUESTS.value()
                response.contentType = "application/json"
                response.characterEncoding = "UTF-8"
                response.writer.write("""{"message": "Too many requests. Please try again later."}""")
                return
            }
        }

        filterChain.doFilter(request, response)
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

    private fun resolveClientIp(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        return if (!forwarded.isNullOrBlank()) forwarded.split(",").first().trim()
        else request.remoteAddr
    }
}
