package com.studentstorefront.service

import com.studentstorefront.entity.PasswordResetToken
import com.studentstorefront.repository.PasswordResetTokenRepository
import com.studentstorefront.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

@Service
@Transactional
class PasswordResetService(
    private val userRepository: UserRepository,
    private val tokenRepository: PasswordResetTokenRepository,
    private val emailService: EmailService,
    private val passwordEncoder: PasswordEncoder
) {

    @Value("\${app.base-url:http://localhost:8080}")
    private lateinit var baseUrl: String

    fun requestReset(email: String) {
        val user = userRepository.findByEmail(email) ?: return

        tokenRepository.deleteByUserUserId(user.userId!!)
        val rawToken = UUID.randomUUID().toString()
        val resetToken = PasswordResetToken(user = user, tokenHash = sha256(rawToken))
        tokenRepository.save(resetToken)

        val resetLink = "$baseUrl/index.html?token=$rawToken#reset-password"
        emailService.sendPasswordResetEmail(email, resetLink)
    }

    fun resetPassword(token: String, newPassword: String) {
        val resetToken = tokenRepository.findByTokenHash(sha256(token))
            ?: throw IllegalArgumentException("Invalid or expired reset token")

        if (resetToken.used) {
            throw IllegalArgumentException("Reset token has already been used")
        }

        if (resetToken.expiresAt.isBefore(LocalDateTime.now())) {
            throw IllegalArgumentException("Reset token has expired")
        }

        val updatedUser = resetToken.user.copy(
            password = passwordEncoder.encode(newPassword)!!
        )
        userRepository.save(updatedUser)

        resetToken.used = true
        tokenRepository.save(resetToken)

        tokenRepository.deleteExpiredTokens(LocalDateTime.now())
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
