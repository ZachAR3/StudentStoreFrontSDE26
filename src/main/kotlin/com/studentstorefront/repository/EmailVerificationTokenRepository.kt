package com.studentstorefront.repository

import com.studentstorefront.entity.EmailVerificationToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface EmailVerificationTokenRepository : JpaRepository<EmailVerificationToken, Long> {

    fun findBySellerEmailAndCodeAndUsedFalse(email: String, code: String): EmailVerificationToken?

    @Modifying
    @Query("DELETE FROM EmailVerificationToken t WHERE t.seller.email = :email")
    fun deleteAllBySellerEmail(email: String)
}
