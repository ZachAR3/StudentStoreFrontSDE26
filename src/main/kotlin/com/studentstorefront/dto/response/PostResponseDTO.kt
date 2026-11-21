package com.studentstorefront.dto.response

import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import java.time.LocalDateTime
import java.math.BigDecimal
import com.fasterxml.jackson.annotation.JsonProperty

data class PostResponseDTO(
    val postId: Long,
    val title: String,
    val price: BigDecimal,
    val mediaUrls: List<String>,
    val description: String,
    val category: Category,
    @get:JsonProperty("isSold")
    val isSold: Boolean,
    val status: PostStatus,
    val createdAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val soldAt: LocalDateTime?,
    val user: PublicUserResponseDTO,
    val email: String? = null,
    val whatsappPhone: String? = null,
    val buyer: PublicUserResponseDTO? = null,
    @get:JsonProperty("isFavourited")
    val isFavourited: Boolean = false,
    /** Seller/buyer/admin only: sold, but the buyer has not confirmed yet, so the sale can still be undone. */
    @get:JsonProperty("awaitingBuyerConfirmation")
    val awaitingBuyerConfirmation: Boolean = false
)
