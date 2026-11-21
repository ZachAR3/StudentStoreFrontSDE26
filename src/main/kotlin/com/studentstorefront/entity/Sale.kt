package com.studentstorefront.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset

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
    val soldAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),

    @Column(name = "buyer_confirmed_at")
    var buyerConfirmedAt: LocalDateTime? = null,

    @Column(nullable = false, length = 100)
    val titleSnapshot: String = post.title,

    @Column(nullable = false, precision = 10, scale = 2)
    val priceSnapshot: java.math.BigDecimal = post.price,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Sale && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: System.identityHashCode(this)
}
