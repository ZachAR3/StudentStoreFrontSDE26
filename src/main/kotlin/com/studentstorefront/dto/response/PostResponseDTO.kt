package com.studentstorefront.dto.response

import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import java.time.LocalDateTime
import java.math.BigDecimal

data class PostResponseDTO(
    val postId: Long,
    val title: String,
    val price: BigDecimal,
    val mediaUrls: List<String>,
    val description: String,
    val category: Category,
    val isSold: Boolean,
    val status: PostStatus,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val seller: SellerResponseDTO
)