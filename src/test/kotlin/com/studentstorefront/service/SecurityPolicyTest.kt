package com.studentstorefront.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Login lockout (BE-07), upload sniffing (BE-11) and the media host allowlist (BE-10). */
class SecurityPolicyTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    @Test
    fun `login locks an account after repeated failures and unlocks after the lockout`() {
        val clock = MutableClock(Instant.parse("2026-01-15T12:00:00Z"))
        val attempts = LoginAttemptService(clock)

        repeat(LoginAttemptService.MAX_FAILURES - 1) { attempts.recordFailure("Someone@Constructor.University") }
        assertNull(attempts.lockedForSeconds("someone@constructor.university"))

        attempts.recordFailure("someone@constructor.university")
        assertEquals(LoginAttemptService.LOCKOUT.seconds, attempts.lockedForSeconds("someone@constructor.university"))

        clock.now = clock.now.plus(LoginAttemptService.LOCKOUT).plusSeconds(1)
        assertNull(attempts.lockedForSeconds("someone@constructor.university"))
    }

    @Test
    fun `successful login clears failures`() {
        val attempts = LoginAttemptService(MutableClock(Instant.parse("2026-01-15T12:00:00Z")))
        repeat(LoginAttemptService.MAX_FAILURES - 1) { attempts.recordFailure("a@constructor.university") }
        attempts.recordSuccess("a@constructor.university")
        attempts.recordFailure("a@constructor.university")
        assertNull(attempts.lockedForSeconds("a@constructor.university"))
    }

    @Test
    fun `failures outside the window start a new count`() {
        val clock = MutableClock(Instant.parse("2026-01-15T12:00:00Z"))
        val attempts = LoginAttemptService(clock)
        repeat(LoginAttemptService.MAX_FAILURES - 1) { attempts.recordFailure("b@constructor.university") }
        clock.now = clock.now.plus(LoginAttemptService.WINDOW).plusSeconds(1)
        attempts.recordFailure("b@constructor.university")
        assertNull(attempts.lockedForSeconds("b@constructor.university"))
    }

    @Test
    fun `image signature accepts real image headers and rejects others`() {
        fun bytes(vararg values: Int) = ByteArray(16).also { values.forEachIndexed { i, v -> it[i] = v.toByte() } }
        fun ascii(text: String) = ByteArray(16).also { text.toByteArray().copyInto(it) }

        assertTrue(ImageSignature.matches(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertTrue(ImageSignature.matches(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertTrue(ImageSignature.matches(ascii("GIF89a")))
        assertTrue(ImageSignature.matches(ascii("RIFF\u0000\u0000\u0000\u0000WEBP")))
        assertTrue(ImageSignature.matches(ascii("\u0000\u0000\u0000\u0018ftypheic")))
        assertFalse(ImageSignature.matches(ascii("<svg xmlns=")))
        assertFalse(ImageSignature.matches(ascii("%PDF-1.7")))
    }

    @Test
    fun `media URLs must be HTTPS on the allowed host`() {
        val ok = "https://res.cloudinary.com/demo/image/upload/sample.jpg"
        assertEquals(ok, MediaUrlPolicy.validate(" $ok "))
        listOf(
            "http://res.cloudinary.com/demo/sample.jpg",
            "https://tracker.example.com/pixel.gif",
            "https://res.cloudinary.com.evil.example/sample.jpg",
            "https://user:pw@res.cloudinary.com/sample.jpg",
            "https://res.cloudinary.com:8443/sample.jpg",
            "not a url"
        ).forEach { url ->
            assertThrows<IllegalArgumentException>(url) { MediaUrlPolicy.validate(url) }
        }
    }
}
