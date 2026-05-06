package com.studentstorefront.repository

import com.studentstorefront.entity.Review
import com.studentstorefront.enums.ReviewDirection
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ReviewRepository : JpaRepository<Review, Long> {
    fun existsByPostPostIdAndDirection(postId: Long, direction: ReviewDirection): Boolean
    fun findByPostPostId(postId: Long): List<Review>
    fun findByRevieweeSellerIdOrderByCreatedAtDesc(sellerId: Long, pageable: Pageable): List<Review>
    fun findByRevieweeSellerIdAndDirection(sellerId: Long, direction: ReviewDirection): List<Review>
    fun deleteByPostPostId(postId: Long)
    fun deleteByReviewerSellerIdOrRevieweeSellerId(reviewerId: Long, revieweeId: Long)
}
