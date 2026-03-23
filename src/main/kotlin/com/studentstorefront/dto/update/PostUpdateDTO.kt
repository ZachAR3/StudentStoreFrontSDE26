package com.studentstorefront.dto.update

import java.time.LocalDateTime

import java.math.BigDecimal

data class PostUpdateDTO(
    val title: String? = null,
    val price: BigDecimal? = null,
    val imageUrl: String? = null,
    val description: String? = null,
    val category: String? = null,
    val isSold: Boolean? = null,
    val expiresAt: LocalDateTime? = null
)