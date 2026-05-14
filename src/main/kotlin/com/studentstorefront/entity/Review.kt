package com.studentstorefront.entity

import com.studentstorefront.enums.ReviewDirection
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "reviews",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_review_sale_direction", columnNames = ["sale_id", "direction"])
    ]
)
data class Review(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    val reviewId: Long? = null,
    @ManyToOne(optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    val sale: Sale,
    @ManyToOne(optional = false)
    @JoinColumn(name = "reviewer_user_id", nullable = false)
    val reviewer: User,
    @ManyToOne(optional = false)
    @JoinColumn(name = "reviewee_user_id", nullable = false)
    val reviewee: User,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val direction: ReviewDirection,
    @Column(nullable = false)
    val rating: Int,
    @Column(length = 500)
    val comment: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
