package com.studentstorefront.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset

@Entity
@Table(
    name = "favourites",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "post_id"])]
)
data class Favourite(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    val post: Post,
    
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Favourite && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: System.identityHashCode(this)
}
