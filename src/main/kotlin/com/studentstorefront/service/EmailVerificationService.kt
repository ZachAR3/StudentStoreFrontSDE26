package com.studentstorefront.service

import com.studentstorefront.entity.EmailVerificationToken
import com.studentstorefront.repository.EmailVerificationTokenRepository
import com.studentstorefront.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class EmailVerificationService(
    private val userRepository: UserRepository,
    private val tokenRepository: EmailVerificationTokenRepository,
    private val emailService: EmailService,
    private val botNotificationService: BotNotificationService,
    private val passwordEncoder: PasswordEncoder
) {

    fun sendCode(email: String) {
        val user = userRepository.findByEmail(email)
            ?: throw IllegalArgumentException("No account found for this email")
        tokenRepository.deleteAllByUserEmail(email)
        val rawCode = EmailVerificationToken.generateCode()
        val token = EmailVerificationToken(
            user = user,
            codeHash = passwordEncoder.encode(rawCode)!!
        )
        tokenRepository.save(token)
        emailService.sendVerificationEmail(email, rawCode)
    }

    fun verifyCode(email: String, code: String) {
        val token = tokenRepository.findTopByUserEmailAndUsedFalseOrderByCreatedAtDesc(email)
            ?: throw IllegalArgumentException("Invalid verification code")
        if (!passwordEncoder.matches(code, token.codeHash)) {
            throw IllegalArgumentException("Invalid verification code")
        }

        if (token.expiresAt.isBefore(LocalDateTime.now())) {
            throw IllegalArgumentException("Verification code has expired")
        }

        token.used = true
        tokenRepository.save(token)
        val enabledSeller = userRepository.save(token.user.copy(isEnabled = true))

        enabledSeller.phoneNumber.takeIf { it.isNotBlank() }?.let { phone ->
            botNotificationService.notifySellerRegistered(phone)
        }
    }

    fun resendCode(email: String) {
        val user = userRepository.findByEmail(email)
            ?: throw IllegalArgumentException("No account found for this email")
        if (user.isEnabled) {
            throw IllegalArgumentException("This account is already verified")
        }
        sendCode(email)
    }
}
