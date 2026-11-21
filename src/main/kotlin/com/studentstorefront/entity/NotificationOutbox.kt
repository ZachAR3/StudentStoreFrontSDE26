package com.studentstorefront.entity

import com.studentstorefront.enums.NotificationType
import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset

@Entity
@Table(
    name = "notification_outbox",
    indexes = [
        Index(name = "idx_notification_outbox_due", columnList = "sent_at,next_attempt_at"),
        Index(name = "idx_notification_outbox_created_at", columnList = "created_at")
    ]
)
data class NotificationOutbox(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "dedupe_key", nullable = false, unique = true, length = 320)
    val dedupeKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val type: NotificationType,

    @Column(nullable = false, length = 255)
    val recipient: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,

    @Column(name = "aggregate_id")
    val aggregateId: Long? = null,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),

    @Column(name = "claimed_at")
    var claimedAt: LocalDateTime? = null,

    @Column(name = "sent_at")
    var sentAt: LocalDateTime? = null,

    // Set when the row is dead-lettered (retries exhausted or expired).
    @Column(name = "failed_at")
    var failedAt: LocalDateTime? = null,

    @Column(name = "last_error", length = 1000)
    var lastError: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
) {
    override fun toString(): String =
        "NotificationOutbox(id=$id, type=$type, recipient=$recipient, aggregateId=$aggregateId, attempts=$attempts, nextAttemptAt=$nextAttemptAt, sentAt=$sentAt, failedAt=$failedAt)"

    override fun equals(other: Any?): Boolean =
        this === other || (other is NotificationOutbox && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: System.identityHashCode(this)
}
