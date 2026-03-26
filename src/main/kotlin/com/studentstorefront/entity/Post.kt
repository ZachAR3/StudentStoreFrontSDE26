package com.studentstorefront.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.math.BigDecimal

@Entity
@Table(name = "posts")
data class Post (
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val postId: Long? = null,
    val title: String = "",
    val price: BigDecimal = BigDecimal.ZERO,
    val imageUrl: String = "",
    val description: String = "",
    @Enumerated(EnumType.STRING)
    val category: Category = Category.UNASSIGNED,
    val isSold: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val expiresAt: LocalDateTime? = null,
    @ManyToOne
    @JoinColumn(name = "seller_id")
    val seller: Seller? = null
)