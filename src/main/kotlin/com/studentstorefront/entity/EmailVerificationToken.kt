package com.studentstorefront.entity

import jakarta.persistence.*
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneOffset

@Entity
@Table(name = "email_verification_tokens")
data class EmailVerificationToken(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val codeHash: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false)
    val expiresAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15),

    @Column(nullable = false)
    var used: Boolean = false,

    @Column(name = "target_email", length = 255)
    val targetEmail: String? = null,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
) {
    companion object {
        private val rng = SecureRandom()
        fun generateCode(): String = (rng.nextInt(900000) + 100000).toString()
    }

    override fun toString(): String =
        "EmailVerificationToken(id=$id, userId=${user.userId}, expiresAt=$expiresAt, used=$used, attempts=$attempts, createdAt=$createdAt)"

    override fun equals(other: Any?): Boolean =
        this === other || (other is EmailVerificationToken && id != null && id == other.id)

    override fun hashCode(): Int = id?.hashCode() ?: System.identityHashCode(this)
}
