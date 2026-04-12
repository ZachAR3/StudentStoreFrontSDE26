package com.studentstorefront.repository

import com.studentstorefront.entity.Seller
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SellerRepository: JpaRepository<Seller, Long> {
    fun findByEmail(email: String): Seller?
    fun existsByEmail(email: String): Boolean
    fun findByPhoneNumber(phoneNumber: String): Seller?
}