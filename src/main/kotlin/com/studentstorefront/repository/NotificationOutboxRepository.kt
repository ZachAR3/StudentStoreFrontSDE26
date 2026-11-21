package com.studentstorefront.repository

import com.studentstorefront.entity.NotificationOutbox
import com.studentstorefront.enums.NotificationType
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface NotificationOutboxRepository : JpaRepository<NotificationOutbox, Long> {
    // lock.timeout -2 makes Hibernate emit FOR UPDATE SKIP LOCKED, so a second
    // app instance takes other rows instead of waiting on these (DB-07).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(
        """
        SELECT n FROM NotificationOutbox n
        WHERE n.sentAt IS NULL
        AND n.failedAt IS NULL
        AND n.nextAttemptAt <= :now
        AND (n.claimedAt IS NULL OR n.claimedAt < :claimBefore)
        ORDER BY n.nextAttemptAt ASC, n.id ASC
        """
    )
    fun findDueForUpdate(
        @Param("now") now: LocalDateTime,
        @Param("claimBefore") claimBefore: LocalDateTime,
        pageable: Pageable
    ): List<NotificationOutbox>

    @Modifying
    @Query(
        value = """
            INSERT INTO notification_outbox
                (dedupe_key, type, recipient, payload, aggregate_id, attempts, next_attempt_at, created_at)
            VALUES (:dedupeKey, :type, :recipient, :payload, :aggregateId, 0, :nextAttemptAt, :createdAt)
            ON CONFLICT (dedupe_key) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIfAbsent(
        @Param("dedupeKey") dedupeKey: String,
        @Param("type") type: String,
        @Param("recipient") recipient: String,
        @Param("payload") payload: String,
        @Param("aggregateId") aggregateId: Long?,
        @Param("nextAttemptAt") nextAttemptAt: LocalDateTime,
        @Param("createdAt") createdAt: LocalDateTime
    ): Int

    @Modifying
    @Query("DELETE FROM NotificationOutbox n WHERE n.sentAt IS NOT NULL AND n.sentAt < :cutoff")
    fun deleteDeliveredBefore(@Param("cutoff") cutoff: LocalDateTime): Int

    /** Removes undelivered rows of one type (e.g. expired codes) created before the cutoff. */
    @Modifying
    @Query("DELETE FROM NotificationOutbox n WHERE n.sentAt IS NULL AND n.type = :type AND n.createdAt < :cutoff")
    fun deleteUndeliveredBefore(
        @Param("type") type: NotificationType,
        @Param("cutoff") cutoff: LocalDateTime
    ): Int

    @Modifying
    @Query("DELETE FROM NotificationOutbox n WHERE n.failedAt IS NOT NULL AND n.failedAt < :cutoff")
    fun deleteFailedBefore(@Param("cutoff") cutoff: LocalDateTime): Int
}
