package com.studentstorefront.scheduler

import com.studentstorefront.repository.PostRepository
import com.studentstorefront.service.NotificationOutboxService
import com.studentstorefront.service.SaleService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.Clock

@Component
class PostExpirationScheduler(
    private val postRepository: PostRepository,
    private val notificationOutboxService: NotificationOutboxService,
    private val saleService: SaleService,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 600_000)
    @Transactional
    fun sendExpirationReminders() {
        val now = LocalDateTime.now(clock)
        val cutoff = now.plus(REMINDER_LEAD_TIME)
        val posts = postRepository.findPostsDueForReminder(now, cutoff)
        if (posts.isEmpty()) return

        log.info("Sending expiration reminders for ${posts.size} post(s)")
        posts.forEach { post ->
            try {
                notificationOutboxService.enqueueRenewalReminder(post)
            } catch (ex: Exception) {
                log.warn("Could not enqueue reminder for post ${post.postId}; it will be retried", ex)
            }
        }
    }

    @Scheduled(fixedRate = 900_000)
    @Transactional
    fun archiveExpiredPosts() {
        val archived = postRepository.archiveExpiredPosts(LocalDateTime.now(clock))
        if (archived > 0) log.info("Archived $archived expired post(s)")
    }

    companion object {
        /** Sellers hear about expiry half a day ahead, so a night's sleep doesn't lose the listing (X-02). */
        val REMINDER_LEAD_TIME: java.time.Duration = java.time.Duration.ofHours(12)
    }

    /** Unconfirmed sales go back on the market after SaleService.UNCONFIRMED_SALE_TTL (BE-02). */
    @Scheduled(fixedRate = 3_600_000, initialDelay = 60_000)
    fun revertUnconfirmedSales() {
        val reverted = saleService.revertUnconfirmedSales()
        if (reverted > 0) log.info("Re-listed $reverted sale(s) the buyer never confirmed")
    }
}
