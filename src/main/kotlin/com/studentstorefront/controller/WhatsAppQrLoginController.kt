package com.studentstorefront.controller

import com.studentstorefront.dto.request.WhatsAppClaimRequestDTO
import com.studentstorefront.dto.request.WhatsAppConfirmRequestDTO
import com.studentstorefront.dto.response.AuthResponseDTO
import com.studentstorefront.dto.response.WhatsAppConfirmResponseDTO
import com.studentstorefront.dto.response.WhatsAppPollResponseDTO
import com.studentstorefront.dto.response.WhatsAppSessionResponseDTO
import com.studentstorefront.service.WhatsAppQrLoginService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/auth/whatsapp")
class WhatsAppQrLoginController(
    private val whatsAppQrLoginService: WhatsAppQrLoginService,
    @Value("\${whatsapp.bot.api-key}") private val botApiKey: String
) {

    @PostMapping("/session")
    fun createSession(request: HttpServletRequest): ResponseEntity<WhatsAppSessionResponseDTO> {
        val ip = request.remoteAddr
        return ResponseEntity.status(HttpStatus.CREATED).body(whatsAppQrLoginService.createSession(ip))
    }

    @GetMapping("/session/{sessionId}")
    fun pollSession(@PathVariable sessionId: String): ResponseEntity<WhatsAppPollResponseDTO> {
        val uuid = runCatching { UUID.fromString(sessionId) }.getOrNull()
            ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(whatsAppQrLoginService.pollSession(uuid))
    }

    @PostMapping("/confirm")
    fun confirmLogin(
        @RequestHeader("X-Bot-Api-Key") apiKey: String,
        @RequestBody body: WhatsAppConfirmRequestDTO
    ): ResponseEntity<WhatsAppConfirmResponseDTO> {
        if (apiKey != botApiKey) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val loginToken = runCatching { UUID.fromString(body.loginToken) }.getOrNull()
            ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(whatsAppQrLoginService.confirmLogin(loginToken, body.phoneNumber))
    }

    @PostMapping("/claim")
    fun claimJwt(@RequestBody body: WhatsAppClaimRequestDTO): ResponseEntity<AuthResponseDTO> {
        val claimToken = runCatching { UUID.fromString(body.claimToken) }.getOrNull()
            ?: return ResponseEntity.badRequest().build()
        val response = whatsAppQrLoginService.claimJwt(claimToken)
            ?: return ResponseEntity.status(HttpStatus.GONE).build()
        return ResponseEntity.ok(response)
    }
}
