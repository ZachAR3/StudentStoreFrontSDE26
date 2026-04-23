package com.studentstorefront.repository

import com.studentstorefront.entity.Favourite
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface FavouriteRepository : JpaRepository<Favourite, Long> {
    fun findBySellerSellerId(sellerId: Long): List<Favourite>
    
    fun existsBySellerSellerIdAndPostPostId(sellerId: Long, postId: Long): Boolean
    
    fun deleteBySellerSellerIdAndPostPostId(sellerId: Long, postId: Long)
    
    fun deleteByPostPostId(postId: Long)
    
    fun deleteBySellerSellerId(sellerId: Long)
}
