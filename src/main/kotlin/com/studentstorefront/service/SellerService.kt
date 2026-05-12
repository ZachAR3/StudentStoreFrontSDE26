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
import com.studentstorefront.repository.WhatsAppLoginSessionRepository
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
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val whatsAppLoginSessionRepository: WhatsAppLoginSessionRepository
) {

    fun createSellerWithToken(request: SellerRequestDTO): Pair<SellerResponseDTO, String> {
        val normalizedPhone = normalizePhone(request.phoneNumber)
        val seller = resolveRegistrationSeller(request, normalizedPhone)
        val token = EmailVerificationToken(seller = seller)
        emailVerificationTokenRepository.save(token)
        return mapToResponseDTO(seller) to token.code
    }

    fun createSeller(sellerRequestDTO: SellerRequestDTO): SellerResponseDTO {
        val normalizedPhone = normalizePhone(sellerRequestDTO.phoneNumber)

        // Check if email already exists
        if (sellerRepository.existsByEmail(sellerRequestDTO.email)) {
            throw IllegalArgumentException("Email already exists: ${sellerRequestDTO.email}")
        }

        // Check if phone number already exists
        if (sellerRepository.findByPhoneNumber(normalizedPhone) != null) {
            throw IllegalArgumentException("Phone number already registered: ${sellerRequestDTO.phoneNumber}")
        }

        val seller = createSellerEntity(sellerRequestDTO, normalizedPhone)
        val savedSeller = sellerRepository.save(seller)
        return mapToResponseDTO(savedSeller)
    }

    @Transactional(readOnly = true)
    fun getAllSellers(pageable: Pageable): Page<SellerResponseDTO> {
        return sellerRepository.findAll(pageable).map { mapToResponseDTO(it) }
    }

    @Transactional(readOnly = true)
    fun getMe(): SellerResponseDTO = mapToResponseDTO(getCurrentSeller())

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
        val seller = sellerRepository.findByPhoneNumber(normalizePhone(phone))
            ?.takeIf { it.isEnabled }
            ?: return null
        return mapToResponseDTO(seller)
    }

    @Transactional(readOnly = true)
    fun searchSellers(query: String, pageable: Pageable): Page<SellerResponseDTO> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) {
            return Page.empty(pageable)
        }
        val currentSellerId = SecurityContextHolder.getContext().authentication
            ?.name
            ?.let { sellerRepository.findByEmail(it)?.sellerId }
        return sellerRepository.searchEnabledSellers(
            escapeLike(normalizedQuery),
            currentSellerId,
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
        clearSellerDependencies(sellerId)
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
        clearSellerDependencies(seller.sellerId!!)

        // Delete the seller
        sellerRepository.delete(seller)
    }

    // Private helper methods
    private fun getCurrentSeller(): Seller {
        val email = SecurityContextHolder.getContext().authentication?.name
            ?: throw IllegalArgumentException("Not authenticated")
        return sellerRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Authenticated seller not found")
    }

    private fun findSellerById(sellerId: Long): Seller {
        return sellerRepository.findById(sellerId)
            .orElseThrow { IllegalArgumentException("Seller not found with id: $sellerId") }
    }

    private fun escapeLike(input: String): String =
        input.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private fun normalizePhone(phone: String): String {
        val digits = phone.replace(Regex("\\D"), "")
        return "+$digits"
    }

    private fun resolveRegistrationSeller(request: SellerRequestDTO, normalizedPhone: String): Seller {
        val emailOwner = sellerRepository.findByEmail(request.email)
        val phoneOwner = sellerRepository.findByPhoneNumber(normalizedPhone)

        if (emailOwner?.isEnabled == true) {
            throw IllegalArgumentException("Email already exists: ${request.email}")
        }

        if (phoneOwner?.isEnabled == true && phoneOwner.sellerId != emailOwner?.sellerId) {
            throw IllegalArgumentException("Phone number already registered: ${request.phoneNumber}")
        }

        return when {
            emailOwner == null && phoneOwner == null -> sellerRepository.save(createSellerEntity(request, normalizedPhone))
            emailOwner != null && phoneOwner != null && emailOwner.sellerId != phoneOwner.sellerId ->
                recycleMergedUnverifiedAccounts(emailOwner, phoneOwner, request, normalizedPhone)
            else -> recycleUnverifiedSeller(emailOwner ?: phoneOwner!!, request, normalizedPhone)
        }
    }

    private fun recycleMergedUnverifiedAccounts(
        emailOwner: Seller,
        phoneOwner: Seller,
        request: SellerRequestDTO,
        normalizedPhone: String
    ): Seller {
        clearSellerDependencies(phoneOwner.sellerId!!)
        sellerRepository.deleteById(phoneOwner.sellerId!!)
        return recycleUnverifiedSeller(emailOwner, request, normalizedPhone)
    }

    private fun recycleUnverifiedSeller(
        seller: Seller,
        request: SellerRequestDTO,
        normalizedPhone: String
    ): Seller {
        clearSellerDependencies(seller.sellerId!!)
        return sellerRepository.save(
            seller.copy(
                name = request.name,
                email = request.email,
                phoneNumber = normalizedPhone,
                password = passwordEncoder.encode(request.password)!!
            )
        )
    }

    private fun clearSellerDependencies(sellerId: Long) {
        emailVerificationTokenRepository.deleteBySellerSellerId(sellerId)
        passwordResetTokenRepository.deleteBySellerSellerId(sellerId)
        favouriteRepository.deleteBySellerSellerId(sellerId)
        reviewRepository.deleteByReviewerSellerIdOrRevieweeSellerId(sellerId, sellerId)
        whatsAppLoginSessionRepository.deleteBySellerId(sellerId)
        postRepository.clearBuyerReferences(sellerId)
        postRepository.deleteAll(postRepository.findBySellerSellerId(sellerId))
    }

    private fun createSellerEntity(sellerRequestDTO: SellerRequestDTO, normalizedPhone: String): Seller {
        return Seller(
            name = sellerRequestDTO.name,
            email = sellerRequestDTO.email,
            phoneNumber = normalizedPhone,
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
