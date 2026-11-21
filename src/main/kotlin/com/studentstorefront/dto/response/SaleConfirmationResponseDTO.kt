package com.studentstorefront.dto.response

import java.time.LocalDateTime

data class SaleConfirmationResponseDTO(
    val saleId: Long,
    val postId: Long,
    val title: String,
    val sellerName: String,
    val soldAt: LocalDateTime
)
