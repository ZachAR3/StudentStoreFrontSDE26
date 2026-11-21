package com.studentstorefront.scheduler

import com.studentstorefront.enums.NotificationType
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.service.BotNotificationService
import com.studentstorefront.service.EmailService
import com.studentstorefront.service.NotificationOutboxService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

@Component
class NotificationOutboxProcessor(
    private val outboxService: NotificationOutboxService,
    private val botNotificationService: BotNotificationService,
    private val emailService: EmailService,
    private val postRepository: PostRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 30_000)
    fun deliverDueNotifications() {
        val now = LocalDateTime.now(clock)
        // Once one bot call fails in a tick, the rest of the bot messages wait
        // for the next tick instead of each blocking on the same timeout (BE-08).
        var botUnavailable = false
        outboxService.claimDue(now).forEach { message ->
            if (botUnavailable && message.type.name.startsWith("BOT_")) {
                outboxService.reschedule(message.id!!, "Skipped: bot unreachable this cycle", now)
                return@forEach
            }
            try {
                when (message.type) {
                    NotificationType.EMAIL_VERIFICATION ->
                        emailService.sendVerificationEmail(message.recipient, message.payload)
                    NotificationType.EMAIL_PASSWORD_RESET ->
                        emailService.sendPasswordResetEmail(message.recipient, message.payload)
                    NotificationType.BOT_USER_REGISTERED ->
                        botNotificationService.notifySellerRegistered(message.recipient)
                    NotificationType.BOT_REVIEW_REQUEST,
                    NotificationType.BOT_RENEWAL_REMINDER ->
                        botNotificationService.sendDirectMessage(message.recipient, message.payload)
                    NotificationType.BOT_ACCOUNT_DELETED ->
                        botNotificationService.notifyAccountDeleted(message.recipient)
                }
                val aggregateId = message.aggregateId
                if (message.type == NotificationType.BOT_RENEWAL_REMINDER && aggregateId != null) {
                    postRepository.markReminderSentIfPending(aggregateId, now)
                }
                outboxService.markDelivered(message.id!!, now)
            } catch (ex: Exception) {
                log.warn("Notification delivery failed for outbox message {}", message.id, ex)
                if (message.type.name.startsWith("BOT_")) botUnavailable = true
                outboxService.reschedule(message.id!!, ex.message ?: ex.javaClass.simpleName, now)
            }
        }
        // Delivered payloads may contain verification codes or reset links.
        outboxService.purgeDelivered(now.minusHours(1))
        outboxService.purgeExpired(now)
    }
}
