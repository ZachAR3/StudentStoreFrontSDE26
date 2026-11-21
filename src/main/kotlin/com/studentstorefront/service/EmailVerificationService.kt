package com.studentstorefront.service

import com.studentstorefront.entity.EmailVerificationToken
import com.studentstorefront.repository.EmailVerificationTokenRepository
import com.studentstorefront.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.Clock

@Service
@Transactional
class EmailVerificationService(
    private val userRepository: UserRepository,
    private val tokenRepository: EmailVerificationTokenRepository,
    private val emailService: EmailService,
    private val passwordEncoder: PasswordEncoder,
    private val notificationOutboxService: NotificationOutboxService? = null,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Autowired(required = false)
    private var eventPublisher: ApplicationEventPublisher? = null

    fun sendCode(email: String) {
        val normalizedEmail = IdentityNormalizer.email(email)
        val user = userRepository.findByEmail(normalizedEmail)
            ?: throw IllegalArgumentException("No account found for this email")
        tokenRepository.deleteByUserUserId(user.userId!!)
        val rawCode = EmailVerificationToken.generateCode()
        val token = EmailVerificationToken(
            user = user,
            codeHash = passwordEncoder.encode(rawCode)!!
        )
        val savedToken = tokenRepository.save(token)
        if (notificationOutboxService != null) {
            notificationOutboxService.enqueueVerificationEmail(savedToken.id!!, normalizedEmail, rawCode)
        } else {
            AfterCommit.run {
                runCatching { emailService.sendVerificationEmail(normalizedEmail, rawCode) }
                    .onFailure { logger.error("Verification email delivery failed", it) }
            }
        }
    }

    @Transactional(noRollbackFor = [InvalidVerificationCodeException::class])
    fun verifyCode(email: String, code: String): com.studentstorefront.entity.User {
        val normalizedEmail = IdentityNormalizer.email(email)
        val token = tokenRepository.findTopByTargetEmailAndUsedFalseOrderByCreatedAtDesc(normalizedEmail)
            ?: tokenRepository.findTopByUserEmailAndUsedFalseOrderByCreatedAtDesc(normalizedEmail)
            ?: throw IllegalArgumentException("Invalid verification code")

        if (token.attempts >= MAX_ATTEMPTS) {
            token.used = true
            tokenRepository.save(token)
            throw InvalidVerificationCodeException("Verification code is locked; request a new code")
        }
        if (!passwordEncoder.matches(code, token.codeHash)) {
            token.attempts += 1
            if (token.attempts >= MAX_ATTEMPTS) token.used = true
            tokenRepository.save(token)
            throw InvalidVerificationCodeException("Invalid verification code")
        }

        if (token.expiresAt.isBefore(LocalDateTime.now(clock))) {
            throw IllegalArgumentException("Verification code has expired")
        }

        token.used = true
        tokenRepository.save(token)
        val targetEmail = token.targetEmail
        val isEmailChange = targetEmail != null
        val updatedUser = if (isEmailChange) {
            if (userRepository.existsByEmail(targetEmail!!)) {
                throw IllegalArgumentException("Email already exists")
            }
            token.user.copy(
                email = targetEmail,
                pendingEmail = null,
                tokenVersion = token.user.tokenVersion + 1
            )
        } else {
            token.user.copy(
                isEnabled = true,
                registrationPending = false,
                tokenVersion = token.user.tokenVersion + 1
            )
        }
        val enabledSeller = userRepository.save(updatedUser)
        if (!isEmailChange) {
            eventPublisher?.publishEvent(
                UserRegisteredEvent(
                    userId = enabledSeller.userId!!,
                    phoneNumber = enabledSeller.phoneNumber,
                    registrationVersion = enabledSeller.tokenVersion
                )
            )
        }

        return enabledSeller
    }

    /**
     * Sends a fresh code to a pending account. Unknown and already-verified
     * emails get the same reply as pending ones so the endpoint cannot be used
     * to discover accounts (BE-09).
     */
    fun resendCode(email: String) {
        val user = userRepository.findByEmail(IdentityNormalizer.email(email)) ?: return
        if (user.isEnabled) return
        sendCode(email)
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}

private class InvalidVerificationCodeException(message: String) : IllegalArgumentException(message)
