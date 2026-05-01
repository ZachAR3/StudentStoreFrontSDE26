package com.studentstorefront.dto.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class VerifyEmailRequestDTO(
    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Email should be valid")
    val email: String,

    @field:NotBlank(message = "Code is required")
    @field:Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
    val code: String
)
