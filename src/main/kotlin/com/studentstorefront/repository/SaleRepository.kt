package com.studentstorefront.repository

import com.studentstorefront.entity.Sale
import org.springframework.data.jpa.repository.JpaRepository

interface SaleRepository : JpaRepository<Sale, Long> {
    fun existsByPostPostId(postId: Long): Boolean
    fun findByPostPostId(postId: Long): Sale?
    fun findBySellerUserId(userId: Long): List<Sale>
    fun findByBuyerUserId(userId: Long): List<Sale>
}
