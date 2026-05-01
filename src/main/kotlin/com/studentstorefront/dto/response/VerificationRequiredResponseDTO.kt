package com.studentstorefront.dto.response

data class VerificationRequiredResponseDTO(
    val status: String = "VERIFICATION_REQUIRED",
    val email: String,
    val message: String = "A verification code has been sent to your email"
)
