package com.studentstorefront.scheduler

import com.studentstorefront.repository.PostRepository
import com.studentstorefront.service.BotNotificationService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
class PostExpirationScheduler(
    private val postRepository: PostRepository,
    private val botNotificationService: BotNotificationService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 600_000)
    @Transactional
    fun sendExpirationReminders() {
        val now = LocalDateTime.now()
        val cutoff = now.plusHours(1)
        val posts = postRepository.findPostsDueForReminder(now, cutoff)
        if (posts.isEmpty()) return

        log.info("Sending expiration reminders for ${posts.size} post(s)")
        posts.forEach { post ->
            botNotificationService.sendRenewalReminder(post)
            postRepository.save(post.copy(reminderSentAt = now))
        }
    }

    @Scheduled(fixedRate = 900_000)
    @Transactional
    fun archiveExpiredPosts() {
        val archived = postRepository.archiveExpiredPosts(LocalDateTime.now())
        if (archived > 0) log.info("Archived $archived expired post(s)")
    }
}
