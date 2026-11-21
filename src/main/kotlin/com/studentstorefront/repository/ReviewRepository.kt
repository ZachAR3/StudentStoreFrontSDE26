package com.studentstorefront.repository

import com.studentstorefront.entity.Review
import com.studentstorefront.enums.ReviewDirection
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

@Repository
interface ReviewRepository : JpaRepository<Review, Long> {
    fun existsBySaleIdAndDirection(saleId: Long, direction: ReviewDirection): Boolean
    fun findByRevieweeUserIdOrderByCreatedAtDescReviewIdDesc(userId: Long, pageable: Pageable): List<Review>
    fun deleteBySalePostPostId(postId: Long)
    fun deleteByReviewerUserIdOrRevieweeUserId(reviewerId: Long, revieweeId: Long)

    interface RatingAggregate {
        fun getAverageRating(): Double?
        fun getReviewCount(): Long
    }

    @Query(
        "SELECT AVG(r.rating) AS averageRating, COUNT(r.reviewId) AS reviewCount FROM Review r WHERE r.reviewee.userId = :userId AND r.direction = :direction"
    )
    fun summarizeByRevieweeAndDirection(
        @Param("userId") userId: Long,
        @Param("direction") direction: ReviewDirection
    ): RatingAggregate
}
