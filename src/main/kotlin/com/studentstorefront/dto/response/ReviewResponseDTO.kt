package com.studentstorefront.dto.response

import com.studentstorefront.enums.ReviewDirection
import java.time.LocalDateTime

data class ReviewResponseDTO(
    val reviewId: Long,
    val postId: Long,
    val postTitle: String,
    val reviewer: SellerResponseDTO,
    val reviewee: SellerResponseDTO,
    val direction: ReviewDirection,
    val rating: Int,
    val comment: String?,
    val createdAt: LocalDateTime
)
