package com.studentstorefront.service

import com.studentstorefront.entity.EmailVerificationToken
import com.studentstorefront.repository.EmailVerificationTokenRepository
import com.studentstorefront.repository.SellerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class EmailVerificationService(
    private val sellerRepository: SellerRepository,
    private val tokenRepository: EmailVerificationTokenRepository,
    private val emailService: EmailService
) {

    fun sendCode(email: String) {
        val seller = sellerRepository.findByEmail(email)
            ?: throw IllegalArgumentException("No account found for this email")
        tokenRepository.deleteAllBySellerEmail(email)
        val token = EmailVerificationToken(seller = seller)
        tokenRepository.save(token)
        emailService.sendVerificationEmail(email, token.code)
    }

    fun verifyCode(email: String, code: String) {
        val token = tokenRepository.findBySellerEmailAndCodeAndUsedFalse(email, code)
            ?: throw IllegalArgumentException("Invalid verification code")

        if (token.expiresAt.isBefore(LocalDateTime.now())) {
            throw IllegalArgumentException("Verification code has expired")
        }

        token.used = true
        tokenRepository.save(token)
        sellerRepository.save(token.seller.copy(isEnabled = true))
    }

    fun resendCode(email: String) {
        val seller = sellerRepository.findByEmail(email)
            ?: throw IllegalArgumentException("No account found for this email")
        if (seller.isEnabled) {
            throw IllegalArgumentException("This account is already verified")
        }
        sendCode(email)
    }
}
