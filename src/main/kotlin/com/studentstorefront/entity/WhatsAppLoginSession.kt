package com.studentstorefront.entity

import com.studentstorefront.enums.WhatsAppSessionStatus
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "whatsapp_login_sessions")
data class WhatsAppLoginSession(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(unique = true, nullable = false)
    val sessionId: UUID = UUID.randomUUID(),

    @Column(unique = true, nullable = false)
    val loginToken: UUID = UUID.randomUUID(),

    @Column(unique = true)
    var claimToken: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: WhatsAppSessionStatus = WhatsAppSessionStatus.PENDING,

    @Column(name = "user_id")
    var userId: Long? = null,

    @Column(name = "phone_number")
    var phoneNumber: String? = null,

    @Column(nullable = false)
    val creatorIp: String,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    val expiresAt: LocalDateTime = LocalDateTime.now().plusMinutes(5),

    var completedAt: LocalDateTime? = null,
    var claimedAt: LocalDateTime? = null
)
