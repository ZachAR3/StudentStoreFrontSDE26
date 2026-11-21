package com.studentstorefront.service

import com.studentstorefront.entity.PasswordResetToken
import com.studentstorefront.repository.PasswordResetTokenRepository
import com.studentstorefront.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.Clock
import java.util.UUID

@Service
@Transactional
class PasswordResetService(
    private val userRepository: UserRepository,
    private val tokenRepository: PasswordResetTokenRepository,
    private val emailService: EmailService,
    private val passwordEncoder: PasswordEncoder,
    private val notificationOutboxService: NotificationOutboxService? = null,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.base-url:http://localhost:8080}")
    private lateinit var baseUrl: String

    fun requestReset(email: String) {
        val normalizedEmail = IdentityNormalizer.email(email)
        val user = userRepository.findByEmail(normalizedEmail) ?: return

        tokenRepository.deleteByUserUserId(user.userId!!)
        val rawToken = UUID.randomUUID().toString()
        val resetToken = PasswordResetToken(user = user, tokenHash = sha256(rawToken))
        val savedToken = tokenRepository.save(resetToken)

        // The token rides in the fragment, which browsers never send to the
        // server, so it stays out of access logs and Referer headers (BE-12).
        val resetLink = "$baseUrl/#reset-token=$rawToken"
        if (notificationOutboxService != null) {
            notificationOutboxService.enqueuePasswordResetEmail(savedToken.id!!, normalizedEmail, resetLink)
        } else {
            AfterCommit.run {
                runCatching { emailService.sendPasswordResetEmail(normalizedEmail, resetLink) }
                    .onFailure { logger.error("Password reset email delivery failed", it) }
            }
        }
    }

    fun resetPassword(token: String, newPassword: String) {
        val resetToken = tokenRepository.findByTokenHash(sha256(token))
            ?: throw IllegalArgumentException("Invalid or expired reset token")

        if (resetToken.used) {
            throw IllegalArgumentException("Reset token has already been used")
        }

        if (resetToken.expiresAt.isBefore(LocalDateTime.now(clock))) {
            throw IllegalArgumentException("Reset token has expired")
        }

        val updatedUser = resetToken.user.copy(
            password = passwordEncoder.encode(newPassword)!!,
            tokenVersion = resetToken.user.tokenVersion + 1
        )
        userRepository.save(updatedUser)

        resetToken.used = true
        tokenRepository.save(resetToken)

        tokenRepository.deleteExpiredTokens(LocalDateTime.now(clock))
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
