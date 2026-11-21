package com.studentstorefront.entity

import com.studentstorefront.enums.Role
import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset

@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    val userId: Long? = null,
    @Version
    @Column(nullable = false)
    val version: Long? = null,
    @Column(nullable = false, length = 100)
    var name: String = "",
    @Column(nullable = false, unique = true, length = 255)
    val email: String = "",
    @Column(name = "phone_number", nullable = false, unique = true, length = 32)
    var phoneNumber: String = "",
    @Column(nullable = false, length = 255)
    var password: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val role: Role = Role.USER,
    @Column(nullable = false)
    val isEnabled: Boolean = false,
    @Column(nullable = false)
    val registrationPending: Boolean = !isEnabled,
    @Column(nullable = false)
    var tokenVersion: Long = 0,
    @Column(length = 255)
    var pendingEmail: String? = null,
    @Column
    var phoneVerifiedAt: LocalDateTime? = null,
    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
) {
    override fun toString(): String =
        "User(userId=$userId, name=$name, email=$email, phoneNumber=$phoneNumber, role=$role, isEnabled=$isEnabled)"

    override fun equals(other: Any?): Boolean =
        this === other || (other is User && userId != null && userId == other.userId)

    override fun hashCode(): Int = userId?.hashCode() ?: System.identityHashCode(this)
}
