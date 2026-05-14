package com.studentstorefront.repository

import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import com.studentstorefront.entity.Post
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface PostRepository: JpaRepository<Post, Long> {

    // --- public listing queries (ACTIVE only) ---

    fun findByCategoryAndStatus(category: Category, status: PostStatus, pageable: Pageable): Page<Post>
    fun findByCategoryAndIsSoldFalseAndStatus(category: Category, status: PostStatus, pageable: Pageable): Page<Post>
    fun findByIsSoldFalseAndStatus(status: PostStatus, pageable: Pageable): Page<Post>

    // --- user profile queries (all statuses — sellers see their own expired posts) ---

    fun findByUserUserId(userId: Long, pageable: Pageable): Page<Post>
    fun findByUserUserId(userId: Long): List<Post>
    fun findByUserUserIdAndIsSoldTrue(userId: Long): List<Post>
    fun findByBuyerUserIdAndIsSoldTrue(userId: Long): List<Post>

    @Modifying
    @Query("UPDATE Post p SET p.buyer = NULL WHERE p.buyer.userId = :buyerUserId")
    fun clearBuyerReferences(@Param("buyerUserId") buyerUserId: Long): Int

    @Query(
        """
        SELECT p FROM Post p
        WHERE p.isSold = false
        AND p.status = com.studentstorefront.enums.PostStatus.ACTIVE
        AND (:category IS NULL OR p.category = :category)
        AND (
            :query IS NULL
            OR LOWER(p.title) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!'
            OR LOWER(p.description) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!'
        )
        """
    )
    fun searchAvailablePosts(
        @Param("query") query: String?,
        @Param("category") category: Category?,
        pageable: Pageable
    ): Page<Post>

    // --- expiration lifecycle queries ---

    @Query(
        """
        SELECT p FROM Post p
        WHERE p.status = com.studentstorefront.enums.PostStatus.ACTIVE
        AND p.reminderSentAt IS NULL
        AND p.expiresAt IS NOT NULL
        AND p.expiresAt > :now
        AND p.expiresAt <= :cutoff
        """
    )
    fun findPostsDueForReminder(
        @Param("now") now: LocalDateTime,
        @Param("cutoff") cutoff: LocalDateTime
    ): List<Post>

    @Modifying
    @Query(
        """
        UPDATE Post p
        SET p.status = com.studentstorefront.enums.PostStatus.ARCHIVED
        WHERE p.status = com.studentstorefront.enums.PostStatus.ACTIVE
        AND p.isSold = false
        AND p.expiresAt IS NOT NULL
        AND p.expiresAt < :now
        """
    )
    fun archiveExpiredPosts(@Param("now") now: LocalDateTime): Int

}
