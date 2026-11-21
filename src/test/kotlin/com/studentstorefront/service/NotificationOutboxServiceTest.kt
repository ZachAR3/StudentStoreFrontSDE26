package com.studentstorefront.service

import com.studentstorefront.entity.NotificationOutbox
import com.studentstorefront.enums.NotificationType
import com.studentstorefront.repository.NotificationOutboxRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional

class NotificationOutboxServiceTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 1, 15, 12, 0)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val repository: NotificationOutboxRepository = mockk<NotificationOutboxRepository>(relaxed = true).also {
        every { it.save(any<NotificationOutbox>()) } answers { firstArg() }
    }
    private val service = NotificationOutboxService(repository, "http://localhost:8080", clock)

    private fun message(
        id: Long,
        type: NotificationType,
        createdAt: LocalDateTime,
        attempts: Int = 0
    ) = NotificationOutbox(
        id = id,
        dedupeKey = "key-$id",
        type = type,
        recipient = "someone@constructor.university",
        payload = "payload",
        attempts = attempts,
        createdAt = createdAt
    )

    @Test
    fun `claimDue dead-letters messages older than their type TTL`() {
        val staleCode = message(1, NotificationType.EMAIL_VERIFICATION, now.minusMinutes(20))
        val freshCode = message(2, NotificationType.EMAIL_VERIFICATION, now.minusMinutes(5))
        val review = message(3, NotificationType.BOT_REVIEW_REQUEST, now.minusDays(2))
        every { repository.findDueForUpdate(any(), any(), any()) } returns listOf(staleCode, freshCode, review)

        val claimed = service.claimDue(now)

        assertEquals(listOf(2L, 3L), claimed.map { it.id })
        assertNotNull(staleCode.failedAt)
        assertEquals(0, staleCode.attempts)
        assertNull(freshCode.failedAt)
        assertEquals(1, freshCode.attempts)
    }

    @Test
    fun `reschedule backs off until max attempts then dead-letters`() {
        val retrying = message(1, NotificationType.BOT_REVIEW_REQUEST, now, attempts = 3)
        every { repository.findById(1) } returns Optional.of(retrying)
        service.reschedule(1, "bot down", now)
        assertNull(retrying.failedAt)
        assertEquals(now.plusSeconds(120), retrying.nextAttemptAt)

        val exhausted = message(2, NotificationType.BOT_REVIEW_REQUEST, now, attempts = NotificationOutboxService.MAX_ATTEMPTS)
        every { repository.findById(2) } returns Optional.of(exhausted)
        service.reschedule(2, "bot down", now)
        assertEquals(now, exhausted.failedAt)
        assertEquals("bot down", exhausted.lastError)
    }

    @Test
    fun `purgeExpired deletes undelivered credentials after their token TTL`() {
        val cutoffs = mutableMapOf<NotificationType, LocalDateTime>()
        val type = slot<NotificationType>()
        val cutoff = slot<LocalDateTime>()
        every { repository.deleteUndeliveredBefore(capture(type), capture(cutoff)) } answers {
            cutoffs[type.captured] = cutoff.captured
            0
        }

        service.purgeExpired(now)

        assertEquals(now.minusMinutes(15), cutoffs[NotificationType.EMAIL_VERIFICATION])
        assertEquals(now.minusMinutes(30), cutoffs[NotificationType.EMAIL_PASSWORD_RESET])
        assertEquals(2, cutoffs.size)
        verify { repository.deleteFailedBefore(now.minusDays(7)) }
    }
}
