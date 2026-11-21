package com.studentstorefront.repository

import com.studentstorefront.entity.Favourite
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.EntityGraph

@Repository
interface FavouriteRepository : JpaRepository<Favourite, Long> {
    @EntityGraph(attributePaths = ["post", "post.user", "post.buyer"])
    fun findByUserUserId(userId: Long, pageable: Pageable): List<Favourite>
    
    fun existsByUserUserIdAndPostPostId(userId: Long, postId: Long): Boolean

    @Query("SELECT f.post.postId FROM Favourite f WHERE f.user.userId = :userId AND f.post.postId IN :postIds")
    fun findPostIdsByUserUserIdAndPostPostIdIn(
        @Param("userId") userId: Long,
        @Param("postIds") postIds: Collection<Long>
    ): Set<Long>

    @Modifying
    @Query(
        value = "INSERT INTO favourites (user_id, post_id) VALUES (:userId, :postId) ON CONFLICT (user_id, post_id) DO NOTHING",
        nativeQuery = true
    )
    fun insertIfAbsent(@Param("userId") userId: Long, @Param("postId") postId: Long): Int
    
    fun deleteByUserUserIdAndPostPostId(userId: Long, postId: Long)
    
    fun deleteByPostPostId(postId: Long)
    
    fun deleteByUserUserId(userId: Long)
}
