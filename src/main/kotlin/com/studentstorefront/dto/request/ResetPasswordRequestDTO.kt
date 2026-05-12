package com.studentstorefront.dto.request

import com.studentstorefront.validation.PASSWORD_MESSAGE
import com.studentstorefront.validation.PASSWORD_REGEX
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class ResetPasswordRequestDTO(
    @field:NotBlank(message = "Token is required")
    val token: String,

    @field:NotBlank(message = "Password is required")
    @field:Pattern(
        regexp = PASSWORD_REGEX,
        message = PASSWORD_MESSAGE
    )
    val newPassword: String
)
