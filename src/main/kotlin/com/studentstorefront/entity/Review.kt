package com.studentstorefront.entity

import com.studentstorefront.enums.ReviewDirection
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "reviews",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_review_post_direction", columnNames = ["post_id", "direction"])
    ]
)
data class Review(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val reviewId: Long? = null,
    @ManyToOne(optional = false)
    @JoinColumn(name = "post_id")
    val post: Post,
    @ManyToOne(optional = false)
    @JoinColumn(name = "reviewer_id")
    val reviewer: Seller,
    @ManyToOne(optional = false)
    @JoinColumn(name = "reviewee_id")
    val reviewee: Seller,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val direction: ReviewDirection,
    @Column(nullable = false)
    val rating: Int,
    @Column(length = 500)
    val comment: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
