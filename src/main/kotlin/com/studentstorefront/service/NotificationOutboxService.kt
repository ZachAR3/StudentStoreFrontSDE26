package com.studentstorefront.service

import com.studentstorefront.entity.NotificationOutbox
import com.studentstorefront.entity.Post
import com.studentstorefront.enums.NotificationType
import com.studentstorefront.repository.NotificationOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.min

@Service
class NotificationOutboxService(
    private val repository: NotificationOutboxRepository,
    @Value("\${app.base-url:http://localhost:8080}") private val appBaseUrl: String,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Transactional
    fun enqueueVerificationEmail(tokenId: Long, email: String, code: String): Boolean = enqueue(
        dedupeKey = "email-verification:$tokenId",
        type = NotificationType.EMAIL_VERIFICATION,
        recipient = email,
        payload = code
    )

    @Transactional
    fun enqueuePasswordResetEmail(tokenId: Long, email: String, link: String): Boolean = enqueue(
        dedupeKey = "email-password-reset:$tokenId",
        type = NotificationType.EMAIL_PASSWORD_RESET,
        recipient = email,
        payload = link
    )

    @Transactional
    fun enqueueRegisteredUser(userId: Long, phoneNumber: String, trigger: String): Boolean = enqueue(
        dedupeKey = "bot-user-registered:$userId:$trigger",
        type = NotificationType.BOT_USER_REGISTERED,
        recipient = phoneNumber,
        payload = "",
        aggregateId = userId
    )

    @Transactional
    fun enqueueAccountDeleted(userId: Long, phoneNumber: String): Boolean = enqueue(
        dedupeKey = "bot-account-deleted:$userId",
        type = NotificationType.BOT_ACCOUNT_DELETED,
        recipient = phoneNumber,
        payload = "",
        aggregateId = userId
    )

    @Transactional
    fun enqueueSaleReviewRequest(event: SaleCompletedEvent): Boolean {
        val phone = event.buyerPhone ?: return false
        val message = "Thanks for buying \"${event.title}\". " +
            "Please leave a quick review for ${event.sellerName} in Student-Store Front: " +
            "$appBaseUrl/#view=profile"
        return enqueue(
            dedupeKey = "bot-sale-review:${event.postId}",
            type = NotificationType.BOT_REVIEW_REQUEST,
            recipient = phone,
            payload = message,
            aggregateId = event.postId
        )
    }

    @Transactional
    fun enqueueRenewalReminder(post: Post): Boolean {
        val phone = post.user?.phoneNumber?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Post ${post.postId} has no user phone")
        val expiresAt = post.expiresAt?.toString() ?: "soon"
        // Stored times are UTC; the reminder is read by people in the server's zone.
        val localExpiry = post.expiresAt
            ?.atOffset(java.time.ZoneOffset.UTC)
            ?.atZoneSameInstant(java.time.ZoneId.systemDefault())
            ?.format(REMINDER_TIME_FORMAT)
            ?: "soon"
        val message = "Your listing \"${post.title}\" expires $localExpiry. " +
            "Reply RENEW ${post.postId} to extend it for 2 more days, or renew it from your profile at $appBaseUrl"
        return enqueue(
            dedupeKey = "bot-renewal:${post.postId}:$expiresAt",
            type = NotificationType.BOT_RENEWAL_REMINDER,
            recipient = phone,
            payload = message,
            aggregateId = post.postId
        )
    }

    @Transactional
    fun claimDue(now: LocalDateTime = LocalDateTime.now(clock), limit: Int = MAX_BATCH): List<NotificationOutbox> {
        val messages = repository.findDueForUpdate(
            now,
            now.minusMinutes(CLAIM_LEASE_MINUTES),
            PageRequest.of(0, limit.coerceIn(1, MAX_BATCH))
        )
        val (expired, live) = messages.partition { isExpired(it, now) }
        expired.forEach {
            // Sending a stale code or reminder hours later is worse than not
            // sending it; dead-letter it instead.
            it.failedAt = now
            it.claimedAt = null
            it.lastError = "Expired before delivery"
        }
        live.forEach {
            it.claimedAt = now
            it.attempts += 1
        }
        repository.saveAll(messages)
        return live
    }

    @Transactional
    fun markDelivered(id: Long, sentAt: LocalDateTime = LocalDateTime.now(clock)) {
        repository.findById(id).ifPresent {
            it.sentAt = sentAt
            it.claimedAt = null
            it.lastError = null
            repository.save(it)
        }
    }

    @Transactional
    fun reschedule(id: Long, error: String, now: LocalDateTime = LocalDateTime.now(clock)) {
        repository.findById(id).ifPresent {
            if (it.attempts >= MAX_ATTEMPTS) {
                log.warn("Outbox message {} ({}) dead-lettered after {} attempts", it.id, it.type, it.attempts)
                it.failedAt = now
                it.claimedAt = null
                it.lastError = error.take(MAX_ERROR_LENGTH)
                repository.save(it)
                return@ifPresent
            }
            val delaySeconds = min(MAX_RETRY_DELAY_SECONDS, INITIAL_RETRY_DELAY_SECONDS shl (it.attempts - 1).coerceAtMost(10))
            it.nextAttemptAt = now.plusSeconds(delaySeconds.toLong())
            it.claimedAt = null
            it.lastError = error.take(MAX_ERROR_LENGTH)
            repository.save(it)
        }
    }

    @Transactional
    fun purgeDelivered(cutoff: LocalDateTime = LocalDateTime.now(clock)) {
        repository.deleteDeliveredBefore(cutoff)
    }

    /**
     * Verification codes and reset links are secrets. Once their token can no
     * longer be used, undelivered rows carrying them are deleted outright, and
     * other dead-lettered rows are kept only briefly for inspection.
     */
    @Transactional
    fun purgeExpired(now: LocalDateTime = LocalDateTime.now(clock)) {
        CREDENTIAL_TYPES.forEach { type ->
            repository.deleteUndeliveredBefore(type, now.minus(TTL_BY_TYPE.getValue(type)))
        }
        repository.deleteFailedBefore(now.minus(FAILED_RETENTION))
    }

    private fun isExpired(message: NotificationOutbox, now: LocalDateTime): Boolean =
        message.createdAt.plus(TTL_BY_TYPE.getValue(message.type)).isBefore(now)

    private fun enqueue(
        dedupeKey: String,
        type: NotificationType,
        recipient: String,
        payload: String,
        aggregateId: Long? = null
    ): Boolean {
        val now = LocalDateTime.now(clock)
        return repository.insertIfAbsent(
            dedupeKey,
            type.name,
            recipient,
            payload,
            aggregateId,
            now,
            now
        ) == 1
    }

    companion object {
        private val REMINDER_TIME_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM 'at' HH:mm z", java.util.Locale.ENGLISH)
        private const val MAX_BATCH = 50
        private const val CLAIM_LEASE_MINUTES = 5L
        private const val INITIAL_RETRY_DELAY_SECONDS = 30
        private const val MAX_RETRY_DELAY_SECONDS = 3600
        private const val MAX_ERROR_LENGTH = 1000
        // With 30s doubling back-off capped at 1h, 10 attempts span ~4 hours.
        internal const val MAX_ATTEMPTS = 10
        private val FAILED_RETENTION: Duration = Duration.ofDays(7)

        // Matches the token lifetimes in EmailVerificationToken (15 min) and
        // PasswordResetToken (30 min); bot messages lose relevance more slowly.
        internal val TTL_BY_TYPE: Map<NotificationType, Duration> = mapOf(
            NotificationType.EMAIL_VERIFICATION to Duration.ofMinutes(15),
            NotificationType.EMAIL_PASSWORD_RESET to Duration.ofMinutes(30),
            NotificationType.BOT_USER_REGISTERED to Duration.ofDays(1),
            NotificationType.BOT_ACCOUNT_DELETED to Duration.ofDays(1),
            NotificationType.BOT_RENEWAL_REMINDER to Duration.ofHours(12),
            NotificationType.BOT_REVIEW_REQUEST to Duration.ofDays(7)
        )
        private val CREDENTIAL_TYPES = listOf(
            NotificationType.EMAIL_VERIFICATION,
            NotificationType.EMAIL_PASSWORD_RESET
        )
    }
}
