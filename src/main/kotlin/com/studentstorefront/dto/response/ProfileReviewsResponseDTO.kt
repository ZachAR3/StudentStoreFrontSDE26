package com.studentstorefront.dto.response

data class RatingSummaryDTO(
    val average: Double?,
    val count: Int
)

data class ProfileReviewsResponseDTO(
    val sellerRating: RatingSummaryDTO,
    val buyerRating: RatingSummaryDTO,
    val recentReviews: List<ReviewResponseDTO>
)
