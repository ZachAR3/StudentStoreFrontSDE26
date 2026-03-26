package com.studentstorefront.service

import com.studentstorefront.dto.request.SellerRequestDTO
import com.studentstorefront.dto.response.SellerResponseDTO
import com.studentstorefront.dto.update.SellerUpdateDTO
import com.studentstorefront.entity.Seller
import com.studentstorefront.repository.SellerRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class SellerService(
    private val sellerRepository: SellerRepository
) {

    fun createSeller(sellerRequestDTO: SellerRequestDTO): SellerResponseDTO {
        // Check if email already exists
        if (sellerRepository.existsByEmail(sellerRequestDTO.email)) {
            throw IllegalArgumentException("Email already exists: ${sellerRequestDTO.email}")
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

    fun updateSeller(sellerId: Long, sellerUpdateDTO: SellerUpdateDTO): SellerResponseDTO {
        val existingSeller = findSellerById(sellerId)

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
        sellerRepository.deleteById(sellerId)
    }

    // Private helper methods
    private fun findSellerById(sellerId: Long): Seller {
        return sellerRepository.findById(sellerId)
            .orElseThrow { IllegalArgumentException("Seller not found with id: $sellerId") }
    }

    private fun createSellerEntity(sellerRequestDTO: SellerRequestDTO): Seller {
        return Seller(
            name = sellerRequestDTO.name,
            email = sellerRequestDTO.email,
            phoneNumber = sellerRequestDTO.phoneNumber,
            password = sellerRequestDTO.password // Note: In production, hash this password
        )
    }

    private fun updateSellerEntity(existingSeller: Seller, sellerUpdateDTO: SellerUpdateDTO): Seller {
        return existingSeller.copy(
            name = sellerUpdateDTO.name ?: existingSeller.name,
            email = sellerUpdateDTO.email ?: existingSeller.email,
            phoneNumber = sellerUpdateDTO.phoneNumber ?: existingSeller.phoneNumber,
            password = sellerUpdateDTO.password ?: existingSeller.password // Note: Hash if updated
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