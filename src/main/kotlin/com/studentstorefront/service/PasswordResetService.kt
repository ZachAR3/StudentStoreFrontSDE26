package com.studentstorefront.service

import com.studentstorefront.entity.PasswordResetToken
import com.studentstorefront.repository.PasswordResetTokenRepository
import com.studentstorefront.repository.SellerRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class PasswordResetService(
    private val sellerRepository: SellerRepository,
    private val tokenRepository: PasswordResetTokenRepository,
    private val emailService: EmailService,
    private val passwordEncoder: PasswordEncoder
) {

    @Value("\${app.base-url:http://localhost:8080}")
    private lateinit var baseUrl: String

    fun requestReset(email: String) {
        val seller = sellerRepository.findByEmail(email) ?: return

        val resetToken = PasswordResetToken(seller = seller)
        tokenRepository.save(resetToken)

        val resetLink = "$baseUrl/index.html?token=${resetToken.token}#reset-password"
        emailService.sendPasswordResetEmail(email, resetLink)
    }

    fun resetPassword(token: String, newPassword: String) {
        val resetToken = tokenRepository.findByToken(token)
            ?: throw IllegalArgumentException("Invalid or expired reset token")

        if (resetToken.used) {
            throw IllegalArgumentException("Reset token has already been used")
        }

        if (resetToken.expiresAt.isBefore(LocalDateTime.now())) {
            throw IllegalArgumentException("Reset token has expired")
        }

        val updatedSeller = resetToken.seller.copy(
            password = passwordEncoder.encode(newPassword)!!
        )
        sellerRepository.save(updatedSeller)

        resetToken.used = true
        tokenRepository.save(resetToken)

        tokenRepository.deleteExpiredTokens(LocalDateTime.now())
    }
}
