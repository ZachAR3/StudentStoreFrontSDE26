package com.studentstorefront.dto.response

data class AuthResponseDTO(
    val token: String,
    val type: String = "Bearer",
    val user: UserResponseDTO
)