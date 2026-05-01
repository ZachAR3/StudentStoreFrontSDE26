package com.studentstorefront.controller

import com.studentstorefront.dto.request.DeleteAccountRequestDTO
import com.studentstorefront.dto.request.SellerRequestDTO
import com.studentstorefront.dto.response.SellerResponseDTO
import com.studentstorefront.dto.update.SellerUpdateDTO
import com.studentstorefront.service.SellerService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/sellers")
@CrossOrigin(origins = ["http://localhost:8080", "http://127.0.0.1:8080", "http://localhost:3000"])
class SellerController(
    private val sellerService: SellerService,
    @Value("\${whatsapp.bot.api-key}") private val botApiKey: String
) {

    /**
     * Self-service account deletion. Requires password re-entry for safety.
     * Mapped before /{sellerId} to prevent Spring from treating "me" as a path variable.
     */
    @DeleteMapping("/me")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun deleteOwnAccount(@Valid @RequestBody request: DeleteAccountRequestDTO): ResponseEntity<Void> {
        sellerService.deleteOwnAccount(request.password)
        return ResponseEntity.noContent().build()
    }

    // TODO: SECURITY VULNERABILITY - This endpoint is currently publicly exposed in SecurityConfig.
    // It should be restricted to ADMINs (e.g., via @PreAuthorize("hasRole('ADMIN')")) because public registration
    // is properly handled by AuthController.
    @PostMapping
    fun createSeller(@Valid @RequestBody sellerRequestDTO: SellerRequestDTO): ResponseEntity<SellerResponseDTO> {
        val createdSeller = sellerService.createSeller(sellerRequestDTO)
        return ResponseEntity.status(HttpStatus.CREATED).body(createdSeller)
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun getAllSellers(
        @PageableDefault(size = 20) pageable: Pageable
    ): ResponseEntity<Page<SellerResponseDTO>> {
        val sellers = sellerService.getAllSellers(pageable)
        return ResponseEntity.ok(sellers)
    }

    @GetMapping("/{sellerId}")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun getSellerById(@PathVariable sellerId: Long): ResponseEntity<SellerResponseDTO> {
        val seller = sellerService.getSellerById(sellerId)
        return ResponseEntity.ok(seller)
    }

    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    fun getSellerByEmail(@PathVariable email: String): ResponseEntity<SellerResponseDTO> {
        val seller = sellerService.getSellerByEmail(email)
        return ResponseEntity.ok(seller)
    }

    // TODO: SECURITY VULNERABILITY - This endpoint leaks user PII because it is completely unauthenticated.
    // It is intended for the bot, so it MUST validate the 'X-Bot-Api-Key' header (like PostController.createPostAsBot).
    @GetMapping("/by-phone")
    fun getSellerByPhone(@RequestParam phone: String): ResponseEntity<SellerResponseDTO> {
        val seller = sellerService.getSellerByPhone(phone)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(seller)
    }

    @PutMapping("/{sellerId}")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun updateSeller(
        @PathVariable sellerId: Long,
        @Valid @RequestBody sellerUpdateDTO: SellerUpdateDTO
    ): ResponseEntity<SellerResponseDTO> {
        val updatedSeller = sellerService.updateSeller(sellerId, sellerUpdateDTO)
        return ResponseEntity.ok(updatedSeller)
    }

    @DeleteMapping("/{sellerId}")
    @PreAuthorize("hasRole('ADMIN')")
    fun deleteSeller(@PathVariable sellerId: Long): ResponseEntity<Void> {
        sellerService.deleteSeller(sellerId)
        return ResponseEntity.noContent().build()
    }
}