package com.studentstorefront.repository

import com.studentstorefront.enums.Category
import com.studentstorefront.entity.Post
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface PostRepository: JpaRepository<Post, Long> {
    fun findByCategory(category: Category, pageable: Pageable): Page<Post>
    fun findByCategoryAndIsSoldFalse(category: Category, pageable: Pageable): Page<Post>
    fun findBySellerSellerId(sellerId: Long, pageable: Pageable): Page<Post>
    fun findBySellerSellerId(sellerId: Long): List<Post>
    fun findByIsSoldFalse(pageable: Pageable): Page<Post>

    @Query(
        """
        SELECT p FROM Post p
        WHERE p.isSold = false
        AND (:category IS NULL OR p.category = :category)
        AND (
            :query IS NULL
            OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%'))
        )
        """
    )
    fun searchAvailablePosts(
        @Param("query") query: String?,
        @Param("category") category: Category?,
        pageable: Pageable
    ): Page<Post>
}
