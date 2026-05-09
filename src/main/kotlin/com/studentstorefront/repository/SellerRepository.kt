package com.studentstorefront.repository

import com.studentstorefront.entity.Seller
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SellerRepository: JpaRepository<Seller, Long> {
    fun findByEmail(email: String): Seller?
    fun existsByEmail(email: String): Boolean
    fun findByPhoneNumber(phoneNumber: String): Seller?

    @Query(
        """
        SELECT s FROM Seller s
        WHERE s.isEnabled = true
        AND s.sellerId <> :excludeSellerId
        AND (
            LOWER(s.name) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!'
            OR LOWER(s.email) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!'
        )
        """
    )
    fun searchEnabledSellers(
        @Param("query") query: String,
        @Param("excludeSellerId") excludeSellerId: Long,
        pageable: Pageable
    ): Page<Seller>
}
