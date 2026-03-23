package com.studentstorefront.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

import java.math.BigDecimal

data class PostRequestDTO(
    @field:NotBlank(message = "Title is required")
    val title: String,

    @field:NotNull(message = "Price is required")
    val price: BigDecimal,

    @field:NotBlank(message = "Image URL is required")
    val imageUrl: String,

    @field:NotBlank(message = "Description is required")
    val description: String,

    @field:NotBlank(message = "Category is required")
    val category: String,

    val isSold: Boolean? = false,

    val expiresAt: LocalDateTime? = null,

    @field:NotNull(message = "Seller ID is required")
    val sellerId: Long?
)