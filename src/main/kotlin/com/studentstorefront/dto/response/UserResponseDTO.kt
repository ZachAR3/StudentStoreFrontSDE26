package com.studentstorefront.dto.response

data class UserResponseDTO(
    val userId: Long,
    val name: String,
    val email: String,
    val phoneNumber: String
)