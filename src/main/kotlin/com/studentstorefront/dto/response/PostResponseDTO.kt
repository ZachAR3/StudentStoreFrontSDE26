package com.studentstorefront.dto.response

import java.time.LocalDateTime

data class PostResponseDTO(
    val postId: Long,
    val imageUrl: String,
    val description: String,
    val category: String,
    val isSold: Boolean,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val seller: SellerResponseDTO
)