package com.studentstorefront.repository

import com.studentstorefront.entity.Review
import com.studentstorefront.enums.ReviewDirection
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ReviewRepository : JpaRepository<Review, Long> {
    fun existsBySaleIdAndDirection(saleId: Long, direction: ReviewDirection): Boolean
    fun findBySaleId(saleId: Long): List<Review>
    fun findByRevieweeUserIdOrderByCreatedAtDesc(userId: Long, pageable: Pageable): List<Review>
    fun findByRevieweeUserIdAndDirection(userId: Long, direction: ReviewDirection): List<Review>
    fun deleteBySalePostPostId(postId: Long)
    fun deleteByReviewerUserIdOrRevieweeUserId(reviewerId: Long, revieweeId: Long)
}
