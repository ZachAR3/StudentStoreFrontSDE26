package com.studentstorefront.repository

import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import com.studentstorefront.entity.Post
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional
import jakarta.persistence.LockModeType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.jpa.repository.EntityGraph

@Repository
interface PostRepository: JpaRepository<Post, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Post p WHERE p.postId = :postId")
    fun findByIdForUpdate(@Param("postId") postId: Long): Optional<Post>

    // --- public listing queries (ACTIVE only) ---

    @EntityGraph(attributePaths = ["user", "buyer"])
    fun findByCategoryAndIsSoldFalseAndStatus(category: Category, status: PostStatus, pageable: Pageable): Page<Post>

    @EntityGraph(attributePaths = ["user", "buyer"])
    fun findByIsSoldFalseAndStatus(status: PostStatus, pageable: Pageable): Page<Post>

    // --- user profile queries (all statuses — sellers see their own expired posts) ---

    @EntityGraph(attributePaths = ["user", "buyer"])
    fun findByUserUserId(userId: Long, pageable: Pageable): Page<Post>

    @EntityGraph(attributePaths = ["user", "buyer"])
    fun findByUserUserIdAndStatusAndIsSoldFalse(userId: Long, status: PostStatus, pageable: Pageable): Page<Post>

    @EntityGraph(attributePaths = ["user", "buyer"])
    fun findByUserUserId(userId: Long): List<Post>

    @EntityGraph(attributePaths = ["user", "buyer"])
    fun findByUserUserIdAndIdempotencyKey(userId: Long, idempotencyKey: String): Post?

    @Modifying
    @Query("UPDATE Post p SET p.buyer = NULL WHERE p.buyer.userId = :buyerUserId")
    fun clearBuyerReferences(@Param("buyerUserId") buyerUserId: Long): Int

    @Query(
        """
        SELECT p FROM Post p JOIN FETCH p.user LEFT JOIN FETCH p.buyer
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
        SELECT p FROM Post p JOIN FETCH p.user
        WHERE p.status = com.studentstorefront.enums.PostStatus.ACTIVE
        AND p.reminderSentAt IS NULL
        AND p.expiresAt IS NOT NULL
        AND p.expiresAt > :now
        AND p.expiresAt <= :cutoff
        """
    )
    fun findPostsDueForReminder(
        @Param("now") now: LocalDateTime,
        @Param("cutoff") cutoff: LocalDateTime,
        pageable: Pageable
    ): List<Post>

    fun findPostsDueForReminder(now: LocalDateTime, cutoff: LocalDateTime): List<Post> =
        findPostsDueForReminder(
            now,
            cutoff,
            PageRequest.of(0, 100, Sort.by(Sort.Order.asc("expiresAt"), Sort.Order.asc("postId")))
        )

    @Modifying
    @Transactional
    @Query("UPDATE Post p SET p.reminderSentAt = :sentAt, p.version = p.version + 1 WHERE p.postId = :postId AND p.status = com.studentstorefront.enums.PostStatus.ACTIVE AND p.isSold = false AND p.reminderSentAt IS NULL")
    fun markReminderSentIfPending(@Param("postId") postId: Long, @Param("sentAt") sentAt: LocalDateTime): Int

    @Modifying
    @Query(
        value = """
            WITH expired AS (
                SELECT post_id
                FROM posts
                WHERE status = 'ACTIVE'
                  AND is_sold = FALSE
                  AND expires_at IS NOT NULL
                  AND expires_at < :now
                ORDER BY expires_at, post_id
                LIMIT 100
            )
            UPDATE posts p
            SET status = 'ARCHIVED', version = version + 1
            FROM expired
            WHERE p.post_id = expired.post_id
            """,
        nativeQuery = true
    )
    fun archiveExpiredPosts(@Param("now") now: LocalDateTime): Int

}
