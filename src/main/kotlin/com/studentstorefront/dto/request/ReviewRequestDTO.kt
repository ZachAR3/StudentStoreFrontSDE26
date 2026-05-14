package com.studentstorefront.dto.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class ReviewRequestDTO(
    @field:NotNull(message = "Sale ID is required")
    val saleId: Long?,
    @field:NotNull(message = "Rating is required")
    @field:Min(value = 1, message = "Rating must be at least 1")
    @field:Max(value = 5, message = "Rating cannot exceed 5")
    val rating: Int?,
    @field:Size(max = 500, message = "Review must be up to 500 characters")
    val comment: String? = null
)
