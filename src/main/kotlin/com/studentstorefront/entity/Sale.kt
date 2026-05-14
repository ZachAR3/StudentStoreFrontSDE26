package com.studentstorefront.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "sales")
data class Sale(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @OneToOne(optional = false)
    @JoinColumn(name = "post_id", nullable = false, unique = true)
    val post: Post,

    @ManyToOne(optional = false)
    @JoinColumn(name = "seller_user_id", nullable = false)
    val seller: User,

    @ManyToOne(optional = false)
    @JoinColumn(name = "buyer_user_id", nullable = false)
    val buyer: User,

    @Column(nullable = false)
    val soldAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
