package com.studentstorefront.repository

import com.studentstorefront.entity.WhatsAppLoginSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

interface WhatsAppLoginSessionRepository : JpaRepository<WhatsAppLoginSession, UUID> {
    fun findBySessionId(sessionId: UUID): WhatsAppLoginSession?
    fun findByLoginToken(loginToken: UUID): WhatsAppLoginSession?
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
}
