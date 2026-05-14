package com.studentstorefront.dto.response

import com.studentstorefront.enums.ReviewDirection

data class ReviewContextResponseDTO(
    val saleId: Long,
    val postId: Long,
    val postTitle: String,
    val direction: ReviewDirection,
    val reviewer: PublicUserResponseDTO,
    val reviewee: PublicUserResponseDTO,
    val alreadyReviewed: Boolean
)
