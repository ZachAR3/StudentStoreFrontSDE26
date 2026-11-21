package com.studentstorefront.service

data class SaleCompletedEvent(
    val postId: Long,
    val title: String,
    val sellerName: String,
    val buyerPhone: String?
)
