package com.studentstorefront.service

import com.studentstorefront.dto.request.ReviewRequestDTO
import com.studentstorefront.dto.response.PublicUserResponseDTO
import com.studentstorefront.dto.response.ProfileReviewsResponseDTO
import com.studentstorefront.dto.response.RatingSummaryDTO
import com.studentstorefront.dto.response.ReviewContextResponseDTO
import com.studentstorefront.dto.response.ReviewResponseDTO
import com.studentstorefront.entity.Review
import com.studentstorefront.entity.Sale
import com.studentstorefront.entity.User
import com.studentstorefront.enums.ReviewDirection
import com.studentstorefront.repository.ReviewRepository
import com.studentstorefront.repository.SaleRepository
import com.studentstorefront.repository.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.round

@Service
@Transactional
class ReviewService(
    private val reviewRepository: ReviewRepository,
    private val saleRepository: SaleRepository,
    private val userRepository: UserRepository
) {

    fun createReview(request: ReviewRequestDTO): ReviewResponseDTO {
        val saleId = request.saleId ?: throw IllegalArgumentException("Sale ID is required")
        val rating = request.rating ?: throw IllegalArgumentException("Rating is required")
        val sale = findReviewableSale(saleId)
        val current = getCurrentUser()
        val direction = resolveDirection(sale, current)

        if (reviewRepository.existsBySaleIdAndDirection(saleId, direction)) {
            throw IllegalArgumentException("Review already submitted for this transaction")
        }

        val reviewee = when (direction) {
            ReviewDirection.BUYER_TO_SELLER -> sale.seller
            ReviewDirection.SELLER_TO_BUYER -> sale.buyer
        }
        val review = Review(
            sale = sale,
            reviewer = current,
            reviewee = reviewee,
            direction = direction,
            rating = rating,
            comment = request.comment?.trim()?.takeIf { it.isNotEmpty() }
        )

        return mapToResponseDTO(reviewRepository.save(review))
    }

    @Transactional(readOnly = true)
    fun getReviewContext(postId: Long): ReviewContextResponseDTO {
        val sale = saleRepository.findByPostPostId(postId)
            ?: throw IllegalArgumentException("No sale found for this post")
        val current = getCurrentUser()
        val direction = resolveDirection(sale, current)
        val reviewee = when (direction) {
            ReviewDirection.BUYER_TO_SELLER -> sale.seller
            ReviewDirection.SELLER_TO_BUYER -> sale.buyer
        }

        return ReviewContextResponseDTO(
            saleId = sale.id!!,
            postId = sale.post.postId!!,
            postTitle = sale.post.title,
            direction = direction,
            reviewer = mapUserToResponseDTO(current),
            reviewee = mapUserToResponseDTO(reviewee),
            alreadyReviewed = reviewRepository.existsBySaleIdAndDirection(sale.id!!, direction)
        )
    }

    @Transactional(readOnly = true)
    fun getPendingReviewContexts(): List<ReviewContextResponseDTO> {
        val current = getCurrentUser()
        val sales = (saleRepository.findBySellerUserId(current.userId!!) + saleRepository.findByBuyerUserId(current.userId!!))
            .distinctBy { it.id }
        return sales.mapNotNull { sale ->
                val direction = runCatching { resolveDirection(sale, current) }.getOrNull() ?: return@mapNotNull null
                if (reviewRepository.existsBySaleIdAndDirection(sale.id!!, direction)) return@mapNotNull null
                val reviewee = when (direction) {
                    ReviewDirection.BUYER_TO_SELLER -> sale.seller
                    ReviewDirection.SELLER_TO_BUYER -> sale.buyer
                }
                ReviewContextResponseDTO(
                    saleId = sale.id!!,
                    postId = sale.post.postId!!,
                    postTitle = sale.post.title,
                    direction = direction,
                    reviewer = mapUserToResponseDTO(current),
                    reviewee = mapUserToResponseDTO(reviewee),
                    alreadyReviewed = false
                )
            }
    }

    @Transactional(readOnly = true)
    fun getProfileReviews(userId: Long): ProfileReviewsResponseDTO {
        if (!userRepository.existsById(userId)) {
            throw IllegalArgumentException("User not found with id: $userId")
        }
        val sellerReviews = reviewRepository.findByRevieweeUserIdAndDirection(
            userId,
            ReviewDirection.BUYER_TO_SELLER
        )
        val buyerReviews = reviewRepository.findByRevieweeUserIdAndDirection(
            userId,
            ReviewDirection.SELLER_TO_BUYER
        )
        val recentReviews = reviewRepository
            .findByRevieweeUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 5))
            .map { mapToResponseDTO(it) }

        return ProfileReviewsResponseDTO(
            sellerRating = summarize(sellerReviews),
            buyerRating = summarize(buyerReviews),
            recentReviews = recentReviews
        )
    }

    private fun findReviewableSale(saleId: Long): Sale {
        return saleRepository.findById(saleId)
            .orElseThrow { IllegalArgumentException("Sale not found with id: $saleId") }
    }

    private fun resolveDirection(sale: Sale, reviewer: User): ReviewDirection {
        return when (reviewer.userId) {
            sale.buyer.userId -> ReviewDirection.BUYER_TO_SELLER
            sale.seller.userId -> ReviewDirection.SELLER_TO_BUYER
            else -> throw AccessDeniedException("You cannot review this transaction")
        }
    }

    private fun summarize(reviews: List<Review>): RatingSummaryDTO {
        if (reviews.isEmpty()) return RatingSummaryDTO(average = null, count = 0)
        val average = reviews.map { it.rating }.average()
        return RatingSummaryDTO(average = round(average * 10) / 10, count = reviews.size)
    }

    private fun mapToResponseDTO(review: Review): ReviewResponseDTO {
        return ReviewResponseDTO(
            reviewId = review.reviewId!!,
            saleId = review.sale.id!!,
            postId = review.sale.post.postId!!,
            postTitle = review.sale.post.title,
            reviewer = mapUserToResponseDTO(review.reviewer),
            reviewee = mapUserToResponseDTO(review.reviewee),
            direction = review.direction,
            rating = review.rating,
            comment = review.comment,
            createdAt = review.createdAt
        )
    }

    private fun mapUserToResponseDTO(user: User): PublicUserResponseDTO {
        return PublicUserResponseDTO(
            userId = user.userId!!,
            name = user.name
        )
    }

    private fun getCurrentUser(): User {
        val email = SecurityContextHolder.getContext().authentication?.name
            ?: throw IllegalArgumentException("Not authenticated")
        return userRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Authenticated user not found")
    }
}
