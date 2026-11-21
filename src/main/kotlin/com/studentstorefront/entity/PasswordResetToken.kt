package com.studentstorefront.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset

@Entity
@Table(name = "password_reset_tokens")
data class PasswordResetToken(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(unique = true, nullable = false)
    val tokenHash: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false)
    val expiresAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30),

    @Column(nullable = false)
    var used: Boolean = false,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
) {
    override fun toString(): String =
        "PasswordResetToken(id=$id, userId=${user.userId}, expiresAt=$expiresAt, used=$used, createdAt=$createdAt)"

    override fun equals(other: Any?): Boolean =
        this === other || (other is PasswordResetToken && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: System.identityHashCode(this)
}
