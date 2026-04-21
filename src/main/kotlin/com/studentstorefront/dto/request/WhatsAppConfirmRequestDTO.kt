package com.studentstorefront.dto.request

data class WhatsAppConfirmRequestDTO(
    val loginToken: String,
    val phoneNumber: String
)
