package com.studentstorefront.dto.request

import jakarta.validation.constraints.NotNull

data class MarkSoldRequestDTO(
    @field:NotNull(message = "Buyer ID is required")
    val buyerUserId: Long?
)
