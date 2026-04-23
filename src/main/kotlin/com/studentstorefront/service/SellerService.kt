package com.studentstorefront.service

import com.studentstorefront.dto.request.SellerRequestDTO
import com.studentstorefront.dto.response.SellerResponseDTO
import com.studentstorefront.dto.update.SellerUpdateDTO
import com.studentstorefront.entity.Seller
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PostRepository
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
    private val passwordEncoder: PasswordEncoder
) {

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

        val updatedSeller = updateSellerEntity(existingSeller, sellerUpdateDTO)
        val savedSeller = sellerRepository.save(updatedSeller)
        return mapToResponseDTO(savedSeller)
    }

    fun deleteSeller(sellerId: Long) {
        if (!sellerRepository.existsById(sellerId)) {
            throw IllegalArgumentException("Seller not found with id: $sellerId")
        }
        favouriteRepository.deleteBySellerSellerId(sellerId)
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

        // Delete all posts owned by this seller (cascades to PostMedia)
        val posts = postRepository.findBySellerSellerId(seller.sellerId!!)
        postRepository.deleteAll(posts)

        // Delete all favourites by this seller
        favouriteRepository.deleteBySellerSellerId(seller.sellerId!!)

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

    private fun createSellerEntity(sellerRequestDTO: SellerRequestDTO): Seller {
        return Seller(
            name = sellerRequestDTO.name,
            email = sellerRequestDTO.email,
            phoneNumber = sellerRequestDTO.phoneNumber,
            password = passwordEncoder.encode(sellerRequestDTO.password)!! // Hash the password
        )
    }

    private fun updateSellerEntity(existingSeller: Seller, sellerUpdateDTO: SellerUpdateDTO): Seller {
        return existingSeller.copy(
            name = sellerUpdateDTO.name ?: existingSeller.name,
            email = sellerUpdateDTO.email ?: existingSeller.email,
            phoneNumber = sellerUpdateDTO.phoneNumber ?: existingSeller.phoneNumber,
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