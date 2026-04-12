package com.studentstorefront.entity

import com.studentstorefront.enums.Role
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "sellers")
data class Seller(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val sellerId: Long? = null,
    val name: String = "",
    @Column(unique = true)
    val email: String = "",
    @Column(unique = true)
    val phoneNumber: String = "",
    val password: String = "",
    @Enumerated(EnumType.STRING)
    val role: Role = Role.SELLER,
    val isEnabled: Boolean = true,
    val createdAt: LocalDateTime = LocalDateTime.now()
)