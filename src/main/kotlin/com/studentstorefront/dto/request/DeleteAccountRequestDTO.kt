package com.studentstorefront.dto.request

import jakarta.validation.constraints.NotBlank

data class DeleteAccountRequestDTO(
    @field:NotBlank(message = "Password is required to confirm account deletion")
    val password: String
)
