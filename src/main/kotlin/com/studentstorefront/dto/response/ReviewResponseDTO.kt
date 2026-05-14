package com.studentstorefront.dto.response

import com.studentstorefront.enums.ReviewDirection
import java.time.LocalDateTime

data class ReviewResponseDTO(
    val reviewId: Long,
    val saleId: Long,
    val postId: Long,
    val postTitle: String,
    val reviewer: PublicUserResponseDTO,
    val reviewee: PublicUserResponseDTO,
    val direction: ReviewDirection,
    val rating: Int,
    val comment: String?,
    val createdAt: LocalDateTime
)
