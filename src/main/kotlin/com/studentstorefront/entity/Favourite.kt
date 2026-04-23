package com.studentstorefront.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "favourites",
    uniqueConstraints = [UniqueConstraint(columnNames = ["seller_id", "post_id"])]
)
data class Favourite(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    val seller: Seller,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    val post: Post,
    
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
