package com.studentstorefront.entity


import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import jakarta.persistence.ManyToOne
import jakarta.persistence.JoinColumn

import java.math.BigDecimal

@Entity
@Table(name = "posts")
data class Post (
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val postId: Long? = null,
    val title: String = "",
    val price: BigDecimal = BigDecimal.ZERO,
    val imageUrl: String = "",
    val description: String = "",
    val category: String = "",
    val isSold: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val expiresAt: LocalDateTime? = null,
    @ManyToOne
    @JoinColumn(name = "seller_id")
    val seller: Seller? = null
)