package com.studentstorefront.entity

import com.studentstorefront.enums.Role
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "sellers")
data class Seller(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val sellerId: Long? = null,
    var name: String = "",
    @Column(unique = true)
    val email: String = "",
    @Column(unique = true)
    var phoneNumber: String = "",
    var password: String = "",
    @Enumerated(EnumType.STRING)
    val role: Role = Role.SELLER,
    val isEnabled: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now()
)