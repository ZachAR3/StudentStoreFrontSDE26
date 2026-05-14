package com.studentstorefront.entity

import jakarta.persistence.*
import java.security.SecureRandom
import java.time.LocalDateTime

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
    val expiresAt: LocalDateTime = LocalDateTime.now().plusMinutes(15),

    @Column(nullable = false)
    var used: Boolean = false,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    companion object {
        private val rng = SecureRandom()
        fun generateCode(): String = (rng.nextInt(900000) + 100000).toString()
    }
}
