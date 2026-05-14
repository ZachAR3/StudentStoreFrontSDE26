package com.studentstorefront.entity

import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import jakarta.persistence.*
import java.time.LocalDateTime
import java.math.BigDecimal

@Entity
@Table(name = "posts")
data class Post (
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id")
    val postId: Long? = null,
    @Column(nullable = false, length = 100)
    val title: String = "",
    @Column(nullable = false, precision = 10, scale = 2)
    val price: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false, length = 1000)
    val description: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val category: Category = Category.OTHER,
    @Column(nullable = false)
    val isSold: Boolean = false,
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val expiresAt: LocalDateTime? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val status: PostStatus = PostStatus.ACTIVE,
    val reminderSentAt: LocalDateTime? = null,
    val soldAt: LocalDateTime? = null,
    @ManyToOne
    @JoinColumn(name = "seller_user_id", nullable = false)
    val user: User? = null,
    @ManyToOne
    @JoinColumn(name = "buyer_user_id")
    val buyer: User? = null,
    @OneToMany(fetch = FetchType.LAZY ,mappedBy = "post", cascade = [CascadeType.ALL])
    val postMedia: List<PostMedia> = emptyList()
)
