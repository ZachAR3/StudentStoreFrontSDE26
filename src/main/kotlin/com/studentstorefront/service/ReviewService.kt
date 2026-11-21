package com.studentstorefront.service

import com.studentstorefront.exception.ResourceNotFoundException
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
            ?.takeIf { it.buyerConfirmedAt != null }
            ?: throw IllegalArgumentException("This sale is awaiting buyer confirmation")
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
        val sales = saleRepository.findPendingReviewsForUser(current.userId!!, org.springframework.data.domain.PageRequest.of(0, MAX_PENDING_REVIEWS))
        return sales.mapNotNull { sale ->
                val direction = runCatching { resolveDirection(sale, current) }.getOrNull() ?: return@mapNotNull null
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
            throw ResourceNotFoundException()
        }
        val sellerSummary = reviewRepository.summarizeByRevieweeAndDirection(
            userId,
            ReviewDirection.BUYER_TO_SELLER
        )
        val buyerSummary = reviewRepository.summarizeByRevieweeAndDirection(
            userId,
            ReviewDirection.SELLER_TO_BUYER
        )
        val recentReviews = reviewRepository
            .findByRevieweeUserIdOrderByCreatedAtDescReviewIdDesc(userId, PageRequest.of(0, 5))
            .map { mapToResponseDTO(it) }

        return ProfileReviewsResponseDTO(
            sellerRating = summarize(sellerSummary),
            buyerRating = summarize(buyerSummary),
            recentReviews = recentReviews
        )
    }

    private fun findReviewableSale(saleId: Long): Sale {
        return saleRepository.findById(saleId)
            .orElseThrow { ResourceNotFoundException() }
            .takeIf { it.buyerConfirmedAt != null }
            ?: throw IllegalArgumentException("This sale is awaiting buyer confirmation")
    }

    private fun resolveDirection(sale: Sale, reviewer: User): ReviewDirection {
        return when (reviewer.userId) {
            sale.buyer.userId -> ReviewDirection.BUYER_TO_SELLER
            sale.seller.userId -> ReviewDirection.SELLER_TO_BUYER
            else -> throw AccessDeniedException("You cannot review this transaction")
        }
    }

    private fun summarize(summary: ReviewRepository.RatingAggregate): RatingSummaryDTO {
        val count = summary.getReviewCount().toInt()
        val average = summary.getAverageRating()?.let { round(it * 10) / 10 }
        return RatingSummaryDTO(average = average, count = count)
    }

    private fun mapToResponseDTO(review: Review): ReviewResponseDTO {
        return ReviewResponseDTO(
            reviewId = review.reviewId!!,
            saleId = review.sale.id!!,
            postId = review.sale.post.postId!!,
            postTitle = review.sale.titleSnapshot,
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
        return userRepository.findByEmail(IdentityNormalizer.email(email))
            ?: throw IllegalArgumentException("Authenticated user not found")
    }

    companion object {
        private const val MAX_PENDING_REVIEWS = 100
    }
}
