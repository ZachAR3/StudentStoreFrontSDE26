package com.studentstorefront.service

import com.studentstorefront.dto.request.ReviewRequestDTO
import com.studentstorefront.dto.response.ProfileReviewsResponseDTO
import com.studentstorefront.dto.response.RatingSummaryDTO
import com.studentstorefront.dto.response.ReviewContextResponseDTO
import com.studentstorefront.dto.response.ReviewResponseDTO
import com.studentstorefront.dto.response.SellerResponseDTO
import com.studentstorefront.entity.Post
import com.studentstorefront.entity.Review
import com.studentstorefront.entity.Seller
import com.studentstorefront.enums.ReviewDirection
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.ReviewRepository
import com.studentstorefront.repository.SellerRepository
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
    private val postRepository: PostRepository,
    private val sellerRepository: SellerRepository
) {

    fun createReview(request: ReviewRequestDTO): ReviewResponseDTO {
        val postId = request.postId ?: throw IllegalArgumentException("Post ID is required")
        val rating = request.rating ?: throw IllegalArgumentException("Rating is required")
        val post = findReviewablePost(postId)
        val current = getCurrentSeller()
        val direction = resolveDirection(post, current)

        if (reviewRepository.existsByPostPostIdAndDirection(postId, direction)) {
            throw IllegalArgumentException("Review already submitted for this transaction")
        }

        val reviewee = when (direction) {
            ReviewDirection.BUYER_TO_SELLER -> post.seller!!
            ReviewDirection.SELLER_TO_BUYER -> post.buyer!!
        }
        val review = Review(
            post = post,
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
        val post = findReviewablePost(postId)
        val current = getCurrentSeller()
        val direction = resolveDirection(post, current)
        val reviewee = when (direction) {
            ReviewDirection.BUYER_TO_SELLER -> post.seller!!
            ReviewDirection.SELLER_TO_BUYER -> post.buyer!!
        }

        return ReviewContextResponseDTO(
            postId = post.postId!!,
            postTitle = post.title,
            direction = direction,
            reviewer = mapSellerToResponseDTO(current),
            reviewee = mapSellerToResponseDTO(reviewee),
            alreadyReviewed = reviewRepository.existsByPostPostIdAndDirection(postId, direction)
        )
    }

    @Transactional(readOnly = true)
    fun getPendingReviewContexts(): List<ReviewContextResponseDTO> {
        val current = getCurrentSeller()
        val sellerPosts = postRepository.findBySellerSellerIdAndIsSoldTrue(current.sellerId!!)
        val buyerPosts = postRepository.findByBuyerSellerIdAndIsSoldTrue(current.sellerId!!)

        return (sellerPosts + buyerPosts)
            .distinctBy { it.postId }
            .mapNotNull { post ->
                val direction = runCatching { resolveDirection(post, current) }.getOrNull() ?: return@mapNotNull null
                val postId = post.postId ?: return@mapNotNull null
                if (reviewRepository.existsByPostPostIdAndDirection(postId, direction)) return@mapNotNull null
                val reviewee = when (direction) {
                    ReviewDirection.BUYER_TO_SELLER -> post.seller!!
                    ReviewDirection.SELLER_TO_BUYER -> post.buyer!!
                }
                ReviewContextResponseDTO(
                    postId = postId,
                    postTitle = post.title,
                    direction = direction,
                    reviewer = mapSellerToResponseDTO(current),
                    reviewee = mapSellerToResponseDTO(reviewee),
                    alreadyReviewed = false
                )
            }
    }

    @Transactional(readOnly = true)
    fun getProfileReviews(sellerId: Long): ProfileReviewsResponseDTO {
        if (!sellerRepository.existsById(sellerId)) {
            throw IllegalArgumentException("Seller not found with id: $sellerId")
        }
        val sellerReviews = reviewRepository.findByRevieweeSellerIdAndDirection(
            sellerId,
            ReviewDirection.BUYER_TO_SELLER
        )
        val buyerReviews = reviewRepository.findByRevieweeSellerIdAndDirection(
            sellerId,
            ReviewDirection.SELLER_TO_BUYER
        )
        val recentReviews = reviewRepository
            .findByRevieweeSellerIdOrderByCreatedAtDesc(sellerId, PageRequest.of(0, 5))
            .map { mapToResponseDTO(it) }

        return ProfileReviewsResponseDTO(
            sellerRating = summarize(sellerReviews),
            buyerRating = summarize(buyerReviews),
            recentReviews = recentReviews
        )
    }

    private fun findReviewablePost(postId: Long): Post {
        val post = postRepository.findById(postId)
            .orElseThrow { IllegalArgumentException("Post not found with id: $postId") }
        if (!post.isSold || post.buyer == null || post.seller == null) {
            throw IllegalArgumentException("Reviews are only available after a completed sale")
        }
        return post
    }

    private fun resolveDirection(post: Post, reviewer: Seller): ReviewDirection {
        return when (reviewer.sellerId) {
            post.buyer?.sellerId -> ReviewDirection.BUYER_TO_SELLER
            post.seller?.sellerId -> ReviewDirection.SELLER_TO_BUYER
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
            postId = review.post.postId!!,
            postTitle = review.post.title,
            reviewer = mapSellerToResponseDTO(review.reviewer),
            reviewee = mapSellerToResponseDTO(review.reviewee),
            direction = review.direction,
            rating = review.rating,
            comment = review.comment,
            createdAt = review.createdAt
        )
    }

    private fun mapSellerToResponseDTO(seller: Seller): SellerResponseDTO {
        return SellerResponseDTO(
            sellerId = seller.sellerId!!,
            name = seller.name,
            email = seller.email,
            phoneNumber = seller.phoneNumber
        )
    }

    private fun getCurrentSeller(): Seller {
        val email = SecurityContextHolder.getContext().authentication?.name
            ?: throw IllegalArgumentException("Not authenticated")
        return sellerRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Authenticated seller not found")
    }
}
