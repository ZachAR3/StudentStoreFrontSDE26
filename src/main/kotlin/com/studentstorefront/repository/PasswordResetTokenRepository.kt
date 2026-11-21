package com.studentstorefront.repository

import com.studentstorefront.entity.PasswordResetToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import java.time.LocalDateTime

interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTokenHash(tokenHash: String): PasswordResetToken?

    fun deleteByUserUserId(userId: Long)

    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :now")
    fun deleteExpiredTokens(now: LocalDateTime): Int

    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.used = true OR t.expiresAt < :now")
    fun deleteExpiredOrUsed(now: LocalDateTime): Int
}
