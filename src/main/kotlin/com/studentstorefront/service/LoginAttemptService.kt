package com.studentstorefront.service

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Per-account login back-off (BE-07). The IP limiter alone lets an attacker
 * spread password guesses for one account over many addresses; this caps
 * failures per email regardless of where they come from.
 */
@Service
class LoginAttemptService(
    private val clock: Clock = Clock.systemUTC()
) {
    private data class Failures(val count: Int, val firstAt: Instant, val lockedUntil: Instant?)

    private val failures = Caffeine.newBuilder()
        .expireAfterWrite(WINDOW.toMinutes() * 2, TimeUnit.MINUTES)
        .maximumSize(100_000)
        .build<String, Failures>()

    /** Seconds until the account may try again, or null when it is not locked. */
    fun lockedForSeconds(email: String): Long? {
        val lockedUntil = failures.getIfPresent(key(email))?.lockedUntil ?: return null
        val remaining = Duration.between(Instant.now(clock), lockedUntil).seconds
        return remaining.takeIf { it > 0 }
    }

    fun recordFailure(email: String) {
        val now = Instant.now(clock)
        failures.asMap().compute(key(email)) { _, previous ->
            val current = previous?.takeIf { it.firstAt.plus(WINDOW).isAfter(now) }
            val count = (current?.count ?: 0) + 1
            Failures(
                count = count,
                firstAt = current?.firstAt ?: now,
                lockedUntil = if (count >= MAX_FAILURES) now.plus(LOCKOUT) else null
            )
        }
    }

    fun recordSuccess(email: String) = failures.invalidate(key(email))

    private fun key(email: String) = IdentityNormalizer.email(email)

    companion object {
        const val MAX_FAILURES = 5
        val WINDOW: Duration = Duration.ofMinutes(15)
        val LOCKOUT: Duration = Duration.ofMinutes(15)
    }
}
