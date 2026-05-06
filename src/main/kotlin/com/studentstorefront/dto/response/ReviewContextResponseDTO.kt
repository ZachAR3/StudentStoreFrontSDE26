package com.studentstorefront.dto.response

import com.studentstorefront.enums.ReviewDirection

data class ReviewContextResponseDTO(
    val postId: Long,
    val postTitle: String,
    val direction: ReviewDirection,
    val reviewer: SellerResponseDTO,
    val reviewee: SellerResponseDTO,
    val alreadyReviewed: Boolean
)
