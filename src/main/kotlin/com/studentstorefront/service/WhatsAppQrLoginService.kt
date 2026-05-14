package com.studentstorefront.service

import com.studentstorefront.dto.response.AuthResponseDTO
import com.studentstorefront.dto.response.UserResponseDTO
import com.studentstorefront.dto.response.WhatsAppConfirmResponseDTO
import com.studentstorefront.dto.response.WhatsAppPollResponseDTO
import com.studentstorefront.dto.response.WhatsAppSessionResponseDTO
import com.studentstorefront.entity.WhatsAppLoginSession
import com.studentstorefront.enums.WhatsAppSessionStatus
import com.studentstorefront.repository.UserRepository
import com.studentstorefront.repository.WhatsAppLoginSessionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class WhatsAppQrLoginService(
    private val sessionRepository: WhatsAppLoginSessionRepository,
    private val userRepository: UserRepository,
    private val jwtService: JwtService,
    private val userDetailsService: UserDetailsService,
    @Value("\${whatsapp.bot.phone}") private val botPhone: String
) {
    companion object {
        private const val CLAIM_TTL_MINUTES = 5L
    }

    fun createSession(creatorIp: String): WhatsAppSessionResponseDTO {
        val session = WhatsAppLoginSession(creatorIp = creatorIp)
        sessionRepository.save(session)
        val qrContent = "https://wa.me/$botPhone?text=login%3A${session.loginToken}"
        return WhatsAppSessionResponseDTO(
            sessionId = session.sessionId.toString(),
            qrContent = qrContent,
            expiresAt = session.expiresAt.format(DateTimeFormatter.ISO_DATE_TIME)
        )
    }

    @Transactional(readOnly = true)
    fun pollSession(sessionId: UUID): WhatsAppPollResponseDTO {
        val session = sessionRepository.findBySessionId(sessionId)
            ?: return WhatsAppPollResponseDTO(status = "NOT_FOUND")
        return WhatsAppPollResponseDTO(
            status = session.status.name,
            claimToken = if (session.status == WhatsAppSessionStatus.COMPLETED) session.claimToken?.toString() else null
        )
    }

    @Transactional
    fun confirmLogin(loginToken: UUID, phoneNumber: String): WhatsAppConfirmResponseDTO {
        val session = sessionRepository.findByLoginToken(loginToken)
            ?: return WhatsAppConfirmResponseDTO(result = "NOT_FOUND")

        if (session.status != WhatsAppSessionStatus.PENDING) {
            val result = if (session.status == WhatsAppSessionStatus.EXPIRED) "EXPIRED" else "ALREADY_USED"
            return WhatsAppConfirmResponseDTO(result = result)
        }

        if (LocalDateTime.now().isAfter(session.expiresAt)) {
            session.status = WhatsAppSessionStatus.EXPIRED
            sessionRepository.save(session)
            return WhatsAppConfirmResponseDTO(result = "EXPIRED")
        }

        val normalizedPhone = normalizePhone(phoneNumber)
        val user = userRepository.findByPhoneNumber(normalizedPhone)
            ?.takeIf { it.isEnabled }
            ?: run {
                session.status = WhatsAppSessionStatus.PHONE_NOT_LINKED
                session.phoneNumber = normalizedPhone
                sessionRepository.save(session)
                return WhatsAppConfirmResponseDTO(result = "PHONE_NOT_LINKED")
            }

        session.status = WhatsAppSessionStatus.COMPLETED
        session.userId = user.userId
        session.phoneNumber = normalizedPhone
        session.completedAt = LocalDateTime.now()
        session.claimToken = UUID.randomUUID()
        sessionRepository.save(session)

        return WhatsAppConfirmResponseDTO(result = "OK")
    }

    private fun normalizePhone(phone: String): String = "+" + phone.replace(Regex("\\D"), "")

    @Transactional
    fun claimJwt(claimToken: UUID): AuthResponseDTO? {
        val session = sessionRepository.findByClaimToken(claimToken) ?: return null

        if (session.status != WhatsAppSessionStatus.COMPLETED) return null

        val completedAt = session.completedAt ?: return null
        if (LocalDateTime.now().isAfter(completedAt.plusMinutes(CLAIM_TTL_MINUTES))) return null

        val user = userRepository.findById(session.userId!!).orElse(null)
        if (user == null || !user.isEnabled) {
            session.status = WhatsAppSessionStatus.PHONE_NOT_LINKED
            session.claimToken = null
            sessionRepository.save(session)
            return null
        }

        val userDetails = userDetailsService.loadUserByUsername(user.email)
        val jwt = jwtService.generateToken(userDetails)

        session.status = WhatsAppSessionStatus.CLAIMED
        session.claimedAt = LocalDateTime.now()
        sessionRepository.save(session)

        return AuthResponseDTO(
            token = jwt,
            user = UserResponseDTO(
                userId = user.userId!!,
                name = user.name,
                email = user.email,
                phoneNumber = user.phoneNumber
            )
        )
    }

    @Scheduled(fixedRate = 300_000)
    @Transactional
    fun expireStaleSessions() {
        sessionRepository.expireStaleSessions(LocalDateTime.now())
    }
}
