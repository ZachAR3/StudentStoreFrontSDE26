package com.studentstorefront.dto.update

import java.time.LocalDateTime

data class PostUpdateDTO(
    val imageUrl: String? = null,
    val description: String? = null,
    val category: String? = null,
    val isSold: Boolean? = null,
    val expiresAt: LocalDateTime? = null
)