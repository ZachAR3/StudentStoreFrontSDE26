package com.studentstorefront.service

import com.studentstorefront.dto.request.SellerRequestDTO
import com.studentstorefront.dto.response.SellerResponseDTO
import com.studentstorefront.dto.update.SellerUpdateDTO
import com.studentstorefront.entity.EmailVerificationToken
import com.studentstorefront.entity.Seller
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.EmailVerificationTokenRepository
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PasswordResetTokenRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.ReviewRepository
import com.studentstorefront.repository.SellerRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class SellerService(
    private val sellerRepository: SellerRepository,
    private val postRepository: PostRepository,
    private val favouriteRepository: FavouriteRepository,
    private val reviewRepository: ReviewRepository,
    private val passwordEncoder: PasswordEncoder,
    private val emailVerificationTokenRepository: EmailVerificationTokenRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository
) {

    fun createSellerWithToken(request: SellerRequestDTO): Pair<SellerResponseDTO, String> {
        val sellerResponse = createSeller(request)
        val seller = sellerRepository.findByEmail(sellerResponse.email)!!
        val token = EmailVerificationToken(seller = seller)
        emailVerificationTokenRepository.save(token)
        return sellerResponse to token.code
    }

    fun createSeller(sellerRequestDTO: SellerRequestDTO): SellerResponseDTO {
        // Check if email already exists
        if (sellerRepository.existsByEmail(sellerRequestDTO.email)) {
            throw IllegalArgumentException("Email already exists: ${sellerRequestDTO.email}")
        }

        // Check if phone number already exists
        if (sellerRepository.findByPhoneNumber(sellerRequestDTO.phoneNumber) != null) {
            throw IllegalArgumentException("Phone number already registered: ${sellerRequestDTO.phoneNumber}")
        }

        val seller = createSellerEntity(sellerRequestDTO)
        val savedSeller = sellerRepository.save(seller)
        return mapToResponseDTO(savedSeller)
    }

    @Transactional(readOnly = true)
    fun getAllSellers(pageable: Pageable): Page<SellerResponseDTO> {
        return sellerRepository.findAll(pageable).map { mapToResponseDTO(it) }
    }

    @Transactional(readOnly = true)
    fun getSellerById(sellerId: Long): SellerResponseDTO {
        val seller = findSellerById(sellerId)
        return mapToResponseDTO(seller)
    }

    @Transactional(readOnly = true)
    fun getSellerByEmail(email: String): SellerResponseDTO {
        val seller = sellerRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Seller not found with email: $email")
        return mapToResponseDTO(seller)
    }

    @Transactional(readOnly = true)
    fun getSellerByPhone(phone: String): SellerResponseDTO? {
        val seller = sellerRepository.findByPhoneNumber(phone) ?: return null
        return mapToResponseDTO(seller)
    }

    @Transactional(readOnly = true)
    fun searchSellers(query: String, pageable: Pageable): Page<SellerResponseDTO> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) {
            return Page.empty(pageable)
        }
        val current = getCurrentSeller()
        return sellerRepository.searchEnabledSellers(
            normalizedQuery,
            current.sellerId!!,
            pageable
        ).map { mapToResponseDTO(it) }
    }

    fun updateSeller(sellerId: Long, sellerUpdateDTO: SellerUpdateDTO): SellerResponseDTO {
        val existingSeller = findSellerById(sellerId)
        val current = getCurrentSeller()
        if (current.role != Role.ADMIN && existingSeller.sellerId != current.sellerId) {
            throw AccessDeniedException("You do not have permission to update this account")
        }

        // Check if email is being updated and if it already exists
        sellerUpdateDTO.email?.let { newEmail ->
            if (newEmail != existingSeller.email && sellerRepository.existsByEmail(newEmail)) {
                throw IllegalArgumentException("Email already exists: $newEmail")
            }
        }

        sellerUpdateDTO.phoneNumber?.let { newPhone ->
            val normalized = normalizePhone(newPhone)
            if (normalized != existingSeller.phoneNumber && sellerRepository.findByPhoneNumber(normalized) != null) {
                throw IllegalArgumentException("Phone number already registered: $newPhone")
            }
        }

        val updatedSeller = updateSellerEntity(existingSeller, sellerUpdateDTO)
        val savedSeller = sellerRepository.save(updatedSeller)
        return mapToResponseDTO(savedSeller)
    }

    fun deleteSeller(sellerId: Long) {
        if (!sellerRepository.existsById(sellerId)) {
            throw IllegalArgumentException("Seller not found with id: $sellerId")
        }
        emailVerificationTokenRepository.deleteBySellerSellerId(sellerId)
        passwordResetTokenRepository.deleteBySellerSellerId(sellerId)
        favouriteRepository.deleteBySellerSellerId(sellerId)
        reviewRepository.deleteByReviewerSellerIdOrRevieweeSellerId(sellerId, sellerId)
        postRepository.clearBuyerReferences(sellerId)
        postRepository.deleteAll(postRepository.findBySellerSellerId(sellerId))
        sellerRepository.deleteById(sellerId)
    }

    /**
     * Allows the currently authenticated user to delete their own account.
     * Requires password re-entry for safety. Deletes all associated posts first.
     */
    fun deleteOwnAccount(password: String) {
        val seller = getCurrentSeller()

        // Verify password
        if (!passwordEncoder.matches(password, seller.password)) {
            throw AccessDeniedException("Incorrect password")
        }

        // Delete dependent rows before removing posts/account.
        emailVerificationTokenRepository.deleteBySellerSellerId(seller.sellerId!!)
        passwordResetTokenRepository.deleteBySellerSellerId(seller.sellerId!!)
        favouriteRepository.deleteBySellerSellerId(seller.sellerId!!)
        reviewRepository.deleteByReviewerSellerIdOrRevieweeSellerId(seller.sellerId!!, seller.sellerId!!)
        postRepository.clearBuyerReferences(seller.sellerId!!)
        postRepository.deleteAll(postRepository.findBySellerSellerId(seller.sellerId!!))

        // Delete the seller
        sellerRepository.delete(seller)
    }

    // Private helper methods
    private fun getCurrentSeller(): Seller {
        val email = SecurityContextHolder.getContext().authentication?.name
        return sellerRepository.findByEmail(email.toString())
            ?: throw IllegalArgumentException("Authenticated seller not found")
    }

    private fun findSellerById(sellerId: Long): Seller {
        return sellerRepository.findById(sellerId)
            .orElseThrow { IllegalArgumentException("Seller not found with id: $sellerId") }
    }

    private fun normalizePhone(phone: String): String {
        val digits = phone.replace(Regex("\\D"), "")
        return "+$digits"
    }

    private fun createSellerEntity(sellerRequestDTO: SellerRequestDTO): Seller {
        return Seller(
            name = sellerRequestDTO.name,
            email = sellerRequestDTO.email,
            phoneNumber = normalizePhone(sellerRequestDTO.phoneNumber),
            password = passwordEncoder.encode(sellerRequestDTO.password)!! // Hash the password
        )
    }

    private fun updateSellerEntity(existingSeller: Seller, sellerUpdateDTO: SellerUpdateDTO): Seller {
        return existingSeller.copy(
            name = sellerUpdateDTO.name ?: existingSeller.name,
            email = sellerUpdateDTO.email ?: existingSeller.email,
            phoneNumber = sellerUpdateDTO.phoneNumber?.let { normalizePhone(it) } ?: existingSeller.phoneNumber,
            password = sellerUpdateDTO.password?.let { passwordEncoder.encode(it) } ?: existingSeller.password
        )
    }

    private fun mapToResponseDTO(seller: Seller): SellerResponseDTO {
        return SellerResponseDTO(
            sellerId = seller.sellerId!!,
            name = seller.name,
            email = seller.email,
            phoneNumber = seller.phoneNumber
        )
    }
}
