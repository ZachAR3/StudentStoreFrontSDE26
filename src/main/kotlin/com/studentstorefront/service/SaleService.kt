package com.studentstorefront.service

import com.studentstorefront.exception.ResourceNotFoundException
import com.studentstorefront.dto.response.SaleConfirmationResponseDTO
import com.studentstorefront.entity.Sale
import com.studentstorefront.enums.PostStatus
import com.studentstorefront.enums.Role
import com.studentstorefront.exception.ConflictException
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.ReviewRepository
import com.studentstorefront.repository.SaleRepository
import com.studentstorefront.repository.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

@Service
@Transactional
class SaleService(
    private val saleRepository: SaleRepository,
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val reviewRepository: ReviewRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock = Clock.systemUTC()
) {
    fun confirmPurchase(postId: Long) {
        val current = currentUser()
        val sale = saleRepository.findByPostPostIdForUpdate(postId)
            ?: throw ResourceNotFoundException()
        if (sale.buyer.userId != current.userId) {
            throw AccessDeniedException("Only the selected buyer can confirm this sale")
        }
        if (sale.buyerConfirmedAt != null) return

        sale.buyerConfirmedAt = LocalDateTime.now(clock)
        saleRepository.save(sale)
        eventPublisher.publishEvent(
            SaleCompletedEvent(
                postId = sale.post.postId!!,
                title = sale.titleSnapshot,
                sellerName = sale.seller.name,
                buyerPhone = sale.buyer.phoneNumber.takeIf { it.isNotBlank() }
            )
        )
    }

    /**
     * Seller (or admin) undoes a mark-sold, for example after picking the
     * wrong buyer. Only possible until the buyer confirms.
     */
    fun cancelSale(postId: Long) {
        val current = currentUser()
        val sale = saleRepository.findByPostPostIdForUpdate(postId)
            ?: throw ResourceNotFoundException()
        if (sale.seller.userId != current.userId && current.role != Role.ADMIN) {
            throw AccessDeniedException("Only the seller can undo this sale")
        }
        if (sale.buyerConfirmedAt != null) {
            throw ConflictException("The buyer already confirmed this sale, so it can no longer be undone")
        }
        revertSale(sale)
    }

    /** The selected buyer says the sale was not theirs. */
    fun declinePurchase(postId: Long) {
        val current = currentUser()
        val sale = saleRepository.findByPostPostIdForUpdate(postId)
            ?: throw ResourceNotFoundException()
        if (sale.buyer.userId != current.userId) {
            throw AccessDeniedException("Only the selected buyer can decline this sale")
        }
        if (sale.buyerConfirmedAt != null) {
            throw ConflictException("You already confirmed this purchase")
        }
        revertSale(sale)
    }

    /**
     * Sales the buyer never confirmed go back on the market after
     * [UNCONFIRMED_SALE_TTL] so a wrong buyer pick can't leave a listing stuck.
     */
    fun revertUnconfirmedSales(now: LocalDateTime = LocalDateTime.now(clock)): Int {
        val stale = saleRepository.findUnconfirmedSoldBefore(now.minus(UNCONFIRMED_SALE_TTL))
        stale.forEach { revertSale(it) }
        return stale.size
    }

    private fun revertSale(sale: Sale) {
        val post = postRepository.findByIdForUpdate(sale.post.postId!!)
            .orElseThrow { ResourceNotFoundException() }
        val now = LocalDateTime.now(clock)
        reviewRepository.deleteBySalePostPostId(post.postId!!)
        // The sale row must be gone before the post leaves the SOLD state;
        // the post trigger rejects a non-sold post that still has a sale.
        saleRepository.delete(sale)
        saleRepository.flush()
        postRepository.saveAndFlush(
            post.copy(
                isSold = false,
                buyer = null,
                soldAt = null,
                status = PostStatus.ACTIVE,
                expiresAt = post.expiresAt?.takeIf { it.isAfter(now.plusHours(1)) } ?: now.plus(RELIST_DURATION),
                reminderSentAt = null
            )
        )
    }

    @Transactional(readOnly = true)
    fun getPendingConfirmations(): List<SaleConfirmationResponseDTO> {
        val current = currentUser()
        return saleRepository.findPendingConfirmationsForBuyer(
            current.userId!!,
            PageRequest.of(0, MAX_PENDING_CONFIRMATIONS)
        ).map { sale ->
            SaleConfirmationResponseDTO(
                saleId = sale.id!!,
                postId = sale.post.postId!!,
                title = sale.titleSnapshot,
                sellerName = sale.seller.name,
                soldAt = sale.soldAt
            )
        }
    }

    private fun currentUser() = userRepository.findByEmail(
        IdentityNormalizer.email(
            SecurityContextHolder.getContext().authentication?.name
                ?: throw AccessDeniedException("Authentication required")
        )
    ) ?: throw ResourceNotFoundException()

    companion object {
        private const val MAX_PENDING_CONFIRMATIONS = 100
        val UNCONFIRMED_SALE_TTL: Duration = Duration.ofDays(7)
        private val RELIST_DURATION: Duration = Duration.ofDays(2)
    }
}
