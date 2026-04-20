package com.studentstorefront.dto.response

data class WhatsAppSessionResponseDTO(
    val sessionId: String,
    val qrContent: String,
    val expiresAt: String
)
