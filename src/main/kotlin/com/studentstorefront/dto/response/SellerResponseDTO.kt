package com.studentstorefront.dto.response

data class SellerResponseDTO(
    val sellerId: Long,
    val name: String,
    val email: String,
    val phoneNumber: String
)