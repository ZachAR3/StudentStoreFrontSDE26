package com.studentstorefront.repository

import com.studentstorefront.entity.WhatsAppLoginSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock

interface WhatsAppLoginSessionRepository : JpaRepository<WhatsAppLoginSession, UUID> {
    fun findBySessionId(sessionId: UUID): WhatsAppLoginSession?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByLoginToken(loginToken: UUID): WhatsAppLoginSession?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByClaimToken(claimToken: UUID): WhatsAppLoginSession?
    fun deleteByUserId(userId: Long)

    @Modifying
    @Query("""
        UPDATE WhatsAppLoginSession s
        SET s.status = com.studentstorefront.enums.WhatsAppSessionStatus.EXPIRED
        WHERE s.status = com.studentstorefront.enums.WhatsAppSessionStatus.PENDING
        AND s.expiresAt < :now
    """)
    fun expireStaleSessions(@Param("now") now: LocalDateTime): Int

    @Modifying
    @Query("DELETE FROM WhatsAppLoginSession s WHERE (s.status IN (com.studentstorefront.enums.WhatsAppSessionStatus.EXPIRED, com.studentstorefront.enums.WhatsAppSessionStatus.CLAIMED) AND COALESCE(s.claimedAt, s.completedAt, s.expiresAt) < :cutoff) OR (s.status = com.studentstorefront.enums.WhatsAppSessionStatus.PHONE_NOT_LINKED AND s.createdAt < :cutoff)")
    fun deleteOldSessions(@Param("cutoff") cutoff: LocalDateTime): Int

    @Modifying
    @Query("UPDATE WhatsAppLoginSession s SET s.status = com.studentstorefront.enums.WhatsAppSessionStatus.EXPIRED WHERE s.status = com.studentstorefront.enums.WhatsAppSessionStatus.COMPLETED AND s.completedAt < :claimCutoff")
    fun expireUnclaimedCompletedSessions(@Param("claimCutoff") claimCutoff: LocalDateTime): Int
}
