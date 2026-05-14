package com.studentstorefront.repository

import com.studentstorefront.entity.Favourite
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface FavouriteRepository : JpaRepository<Favourite, Long> {
    fun findByUserUserId(userId: Long): List<Favourite>
    
    fun existsByUserUserIdAndPostPostId(userId: Long, postId: Long): Boolean
    
    fun deleteByUserUserIdAndPostPostId(userId: Long, postId: Long)
    
    fun deleteByPostPostId(postId: Long)
    
    fun deleteByUserUserId(userId: Long)
}
