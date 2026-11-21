package com.studentstorefront.scheduler

import com.studentstorefront.entity.Post
import com.studentstorefront.entity.User
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.service.NotificationOutboxService
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset

class PostExpirationSchedulerTest {

    private val postRepository: PostRepository = mockk()
    private val notificationOutboxService: NotificationOutboxService = mockk(relaxed = true)

    private val scheduler = PostExpirationScheduler(postRepository, notificationOutboxService, mockk(relaxed = true))

    // ── sendExpirationReminders ────────────────────────────────────────────────

    @Test
    fun `sendExpirationReminders does nothing when no posts are due`() {
        every { postRepository.findPostsDueForReminder(any(), any()) } returns emptyList()

        scheduler.sendExpirationReminders()

        verify(exactly = 0) { notificationOutboxService.enqueueRenewalReminder(any()) }
        verify(exactly = 0) { postRepository.save(any()) }
    }

    @Test
    fun `sendExpirationReminders sends reminder for each due post`() {
        val post1 = buildPost(1L)
        val post2 = buildPost(2L)
        every { postRepository.findPostsDueForReminder(any(), any()) } returns listOf(post1, post2)
        every { postRepository.markReminderSentIfPending(any(), any()) } returns 1

        scheduler.sendExpirationReminders()

        verify(exactly = 1) { notificationOutboxService.enqueueRenewalReminder(post1) }
        verify(exactly = 1) { notificationOutboxService.enqueueRenewalReminder(post2) }
    }

    @Test
    fun `sendExpirationReminders enqueues each reminder`() {
        val post = buildPost(1L)
        every { postRepository.findPostsDueForReminder(any(), any()) } returns listOf(post)

        scheduler.sendExpirationReminders()

        verify(exactly = 1) { notificationOutboxService.enqueueRenewalReminder(post) }
    }

    @Test
    fun `sendExpirationReminders queries the reminder lead-time window`() {
        val nowSlot = slot<LocalDateTime>()
        val cutoffSlot = slot<LocalDateTime>()
        every { postRepository.findPostsDueForReminder(capture(nowSlot), capture(cutoffSlot)) } returns emptyList()

        val before = LocalDateTime.now(ZoneOffset.UTC)
        scheduler.sendExpirationReminders()
        val after = LocalDateTime.now(ZoneOffset.UTC)

        assertTrue(nowSlot.captured.isAfter(before.minusSeconds(1)))
        assertTrue(nowSlot.captured.isBefore(after.plusSeconds(1)))
        assertEquals(nowSlot.captured.plus(PostExpirationScheduler.REMINDER_LEAD_TIME), cutoffSlot.captured)
    }

    // ── archiveExpiredPosts ────────────────────────────────────────────────────

    @Test
    fun `archiveExpiredPosts delegates to repository`() {
        every { postRepository.archiveExpiredPosts(any()) } returns 0

        scheduler.archiveExpiredPosts()

        verify(exactly = 1) { postRepository.archiveExpiredPosts(any()) }
    }

    @Test
    fun `archiveExpiredPosts passes current time to repository`() {
        val timeSlot = slot<LocalDateTime>()
        every { postRepository.archiveExpiredPosts(capture(timeSlot)) } returns 2

        val before = LocalDateTime.now(ZoneOffset.UTC)
        scheduler.archiveExpiredPosts()
        val after = LocalDateTime.now(ZoneOffset.UTC)

        assertTrue(timeSlot.captured.isAfter(before.minusSeconds(1)))
        assertTrue(timeSlot.captured.isBefore(after.plusSeconds(1)))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun buildPost(id: Long) = Post(
        postId = id,
        title = "Test Post $id",
        price = BigDecimal("10.00"),
        description = "A test post description",
        category = Category.OTHER,
        expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30),
        status = PostStatus.ACTIVE,
        user = User(
            userId = 1L,
            name = "User",
            email = "user@test.com",
            phoneNumber = "+1234567890",
            password = "hash",
            role = Role.USER
        )
    )
}
