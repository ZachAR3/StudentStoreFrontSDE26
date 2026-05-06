package com.studentstorefront.scheduler

import com.studentstorefront.entity.Post
import com.studentstorefront.entity.Seller
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.service.BotNotificationService
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class PostExpirationSchedulerTest {

    private val postRepository: PostRepository = mockk()
    private val botNotificationService: BotNotificationService = mockk(relaxed = true)

    private val scheduler = PostExpirationScheduler(postRepository, botNotificationService)

    // ── sendExpirationReminders ────────────────────────────────────────────────

    @Test
    fun `sendExpirationReminders does nothing when no posts are due`() {
        every { postRepository.findPostsDueForReminder(any(), any()) } returns emptyList()

        scheduler.sendExpirationReminders()

        verify(exactly = 0) { botNotificationService.sendRenewalReminder(any()) }
        verify(exactly = 0) { postRepository.save(any()) }
    }

    @Test
    fun `sendExpirationReminders sends reminder for each due post`() {
        val post1 = buildPost(1L)
        val post2 = buildPost(2L)
        every { postRepository.findPostsDueForReminder(any(), any()) } returns listOf(post1, post2)
        every { postRepository.save(any()) } returnsArgument 0

        scheduler.sendExpirationReminders()

        verify(exactly = 1) { botNotificationService.sendRenewalReminder(post1) }
        verify(exactly = 1) { botNotificationService.sendRenewalReminder(post2) }
    }

    @Test
    fun `sendExpirationReminders stamps reminderSentAt on each saved post`() {
        val post = buildPost(1L)
        val savedSlot = slot<Post>()
        every { postRepository.findPostsDueForReminder(any(), any()) } returns listOf(post)
        every { postRepository.save(capture(savedSlot)) } returnsArgument 0

        scheduler.sendExpirationReminders()

        assertNotNull(savedSlot.captured.reminderSentAt)
    }

    @Test
    fun `sendExpirationReminders queries the correct 1-hour window`() {
        val nowSlot = slot<LocalDateTime>()
        val cutoffSlot = slot<LocalDateTime>()
        every { postRepository.findPostsDueForReminder(capture(nowSlot), capture(cutoffSlot)) } returns emptyList()

        val before = LocalDateTime.now()
        scheduler.sendExpirationReminders()
        val after = LocalDateTime.now()

        assertTrue(nowSlot.captured.isAfter(before.minusSeconds(1)))
        assertTrue(nowSlot.captured.isBefore(after.plusSeconds(1)))
        assertTrue(cutoffSlot.captured.isAfter(nowSlot.captured.plusMinutes(59)))
        assertTrue(cutoffSlot.captured.isBefore(nowSlot.captured.plusMinutes(61)))
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

        val before = LocalDateTime.now()
        scheduler.archiveExpiredPosts()
        val after = LocalDateTime.now()

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
        expiresAt = LocalDateTime.now().plusMinutes(30),
        status = PostStatus.ACTIVE,
        seller = Seller(
            sellerId = 1L,
            name = "Seller",
            email = "seller@test.com",
            phoneNumber = "+1234567890",
            password = "hash",
            role = Role.SELLER
        )
    )
}
