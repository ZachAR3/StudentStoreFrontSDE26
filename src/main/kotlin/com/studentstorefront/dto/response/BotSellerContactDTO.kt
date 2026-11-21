package com.studentstorefront.dto.response

/**
 * Seller contact details returned to the WhatsApp bot for a registered,
 * verified requester. Mirrors the web policy: only signed-in members may
 * contact sellers.
 */
data class BotSellerContactDTO(
    val postId: Long,
    val title: String,
    val sellerName: String,
    val whatsappPhone: String
)
