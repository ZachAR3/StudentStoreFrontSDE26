package com.studentstorefront.scheduler

import com.studentstorefront.repository.EmailVerificationTokenRepository
import com.studentstorefront.repository.PasswordResetTokenRepository
import com.studentstorefront.repository.WhatsAppLoginSessionRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.Clock

@Component
class CredentialCleanupScheduler(
    private val emailVerificationTokenRepository: EmailVerificationTokenRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val whatsAppLoginSessionRepository: WhatsAppLoginSessionRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    @Autowired(required = false)
    private var jdbcTemplate: JdbcTemplate? = null

    @Scheduled(fixedRate = 300_000)
    @Transactional
    fun cleanExpiredCredentials() {
        val now = LocalDateTime.now(clock)
        whatsAppLoginSessionRepository.expireStaleSessions(now)
        whatsAppLoginSessionRepository.expireUnclaimedCompletedSessions(now.minusMinutes(5))
        whatsAppLoginSessionRepository.deleteOldSessions(now.minusHours(1))
        emailVerificationTokenRepository.deleteExpiredOrUsed(now)
        passwordResetTokenRepository.deleteExpiredOrUsed(now)
        jdbcTemplate?.update(
            "DELETE FROM api_rate_limit_windows WHERE window_start < ?",
            Timestamp.valueOf(
                    LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
                    .minusHours(1)
                    .withSecond(0)
                    .withNano(0)
            )
        )
    }
}
