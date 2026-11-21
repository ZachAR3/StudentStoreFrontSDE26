package com.studentstorefront.repository

import com.studentstorefront.entity.Sale
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType

interface SaleRepository : JpaRepository<Sale, Long> {
    fun existsByPostPostId(postId: Long): Boolean
    fun findByPostPostId(postId: Long): Sale?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Sale s WHERE s.post.postId = :postId")
    fun findByPostPostIdForUpdate(@Param("postId") postId: Long): Sale?
    @Query("SELECT s FROM Sale s WHERE s.buyerConfirmedAt IS NULL AND s.soldAt < :cutoff ORDER BY s.soldAt")
    fun findUnconfirmedSoldBefore(@Param("cutoff") cutoff: java.time.LocalDateTime): List<Sale>
    fun findBySellerUserId(userId: Long): List<Sale>
    fun findByBuyerUserId(userId: Long): List<Sale>
    fun existsBySellerUserIdOrBuyerUserId(sellerUserId: Long, buyerUserId: Long): Boolean

    @Query(
        """
        SELECT s FROM Sale s
        WHERE s.buyer.userId = :userId
        AND s.buyerConfirmedAt IS NULL
        ORDER BY s.soldAt DESC, s.id DESC
        """
    )
    fun findPendingConfirmationsForBuyer(
        @Param("userId") userId: Long,
        pageable: Pageable
    ): List<Sale>

    @Query(
        """
        SELECT s FROM Sale s
        WHERE s.buyerConfirmedAt IS NOT NULL
        AND ((s.seller.userId = :userId AND NOT EXISTS (
            SELECT r.reviewId FROM Review r WHERE r.sale.id = s.id AND r.direction = com.studentstorefront.enums.ReviewDirection.SELLER_TO_BUYER
        ))
        OR (s.buyer.userId = :userId AND NOT EXISTS (
            SELECT r.reviewId FROM Review r WHERE r.sale.id = s.id AND r.direction = com.studentstorefront.enums.ReviewDirection.BUYER_TO_SELLER
        )))
        ORDER BY s.soldAt DESC, s.id DESC
        """
    )
    fun findPendingReviewsForUser(@Param("userId") userId: Long, pageable: Pageable): List<Sale>
}
