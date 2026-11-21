package com.studentstorefront.repository

import com.studentstorefront.entity.EmailVerificationToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import java.time.LocalDateTime

interface EmailVerificationTokenRepository : JpaRepository<EmailVerificationToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findTopByUserEmailAndUsedFalseOrderByCreatedAtDesc(email: String): EmailVerificationToken?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findTopByTargetEmailAndUsedFalseOrderByCreatedAtDesc(targetEmail: String): EmailVerificationToken?

    fun deleteByUserUserId(userId: Long)

    @Modifying
    @Query("DELETE FROM EmailVerificationToken t WHERE t.expiresAt < :now OR t.used = true")
    fun deleteExpiredOrUsed(now: LocalDateTime): Int
}
