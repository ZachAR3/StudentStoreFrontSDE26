package com.studentStoreFront.Backend

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table


@Entity
@Table(name = "sellers")
data class Seller (
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val sellerId: Long? = null,
    val name: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val password: String = ""
)