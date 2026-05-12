package com.studentstorefront.service

import com.studentstorefront.dto.request.SellerRequestDTO
import com.studentstorefront.entity.Seller
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.EmailVerificationTokenRepository
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PasswordResetTokenRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.ReviewRepository
import com.studentstorefront.repository.SellerRepository
import com.studentstorefront.repository.WhatsAppLoginSessionRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder

class SellerServiceTest {

    private val sellerRepository: SellerRepository = mockk()
    private val postRepository: PostRepository = mockk()
    private val favouriteRepository: FavouriteRepository = mockk()
    private val reviewRepository: ReviewRepository = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val emailVerificationTokenRepository: EmailVerificationTokenRepository = mockk()
    private val passwordResetTokenRepository: PasswordResetTokenRepository = mockk()
    private val whatsAppLoginSessionRepository: WhatsAppLoginSessionRepository = mockk()

    private val service = SellerService(
        sellerRepository = sellerRepository,
        postRepository = postRepository,
        favouriteRepository = favouriteRepository,
        reviewRepository = reviewRepository,
        passwordEncoder = passwordEncoder,
        emailVerificationTokenRepository = emailVerificationTokenRepository,
        passwordResetTokenRepository = passwordResetTokenRepository,
        whatsAppLoginSessionRepository = whatsAppLoginSessionRepository
    )

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `createSellerWithToken reuses unverified seller found by phone`() {
        val existingSeller = buildSeller(
            sellerId = 7L,
            email = "wrong@constructor.university",
            phoneNumber = "+49123456789",
            password = "old-hash",
            isEnabled = false
        )
        val request = SellerRequestDTO(
            name = "Updated Seller",
            email = "correct@constructor.university",
            phoneNumber = "49123456789",
            password = "Password1!"
        )

        every { sellerRepository.findByEmail(request.email) } returns null
        every { sellerRepository.findByPhoneNumber("+49123456789") } returns existingSeller
        every { passwordEncoder.encode(request.password) } returns "new-hash"
        stubSellerDependencyCleanup(existingSeller.sellerId!!)
        every { sellerRepository.save(match { candidate ->
            candidate.sellerId == existingSeller.sellerId
                && candidate.name == request.name
                && candidate.email == request.email
                && candidate.phoneNumber == "+49123456789"
                && candidate.password == "new-hash"
        }) } answers { firstArg() }
        every { emailVerificationTokenRepository.save(any()) } returnsArgument 0

        val (seller, _) = service.createSellerWithToken(request)

        assertEquals(request.email, seller.email)
        assertEquals("+49123456789", seller.phoneNumber)
        verify(exactly = 1) { whatsAppLoginSessionRepository.deleteBySellerId(existingSeller.sellerId!!) }
        verify(exactly = 1) { sellerRepository.save(any()) }
        verify(exactly = 1) { emailVerificationTokenRepository.save(any()) }
    }

    @Test
    fun `createSellerWithToken merges separate unverified email and phone reservations`() {
        val emailOwner = buildSeller(
            sellerId = 11L,
            email = "correct@constructor.university",
            phoneNumber = "+49111000000",
            password = "old-email-hash",
            isEnabled = false
        )
        val phoneOwner = buildSeller(
            sellerId = 12L,
            email = "wrong@constructor.university",
            phoneNumber = "+49123456789",
            password = "old-phone-hash",
            isEnabled = false
        )
        val request = SellerRequestDTO(
            name = "Merged Seller",
            email = "correct@constructor.university",
            phoneNumber = "49123456789",
            password = "Password1!"
        )

        every { sellerRepository.findByEmail(request.email) } returns emailOwner
        every { sellerRepository.findByPhoneNumber("+49123456789") } returns phoneOwner
        every { passwordEncoder.encode(request.password) } returns "merged-hash"
        stubSellerDependencyCleanup(emailOwner.sellerId!!)
        stubSellerDependencyCleanup(phoneOwner.sellerId!!)
        every { sellerRepository.deleteById(phoneOwner.sellerId!!) } just Runs
        every { sellerRepository.save(match { candidate ->
            candidate.sellerId == emailOwner.sellerId
                && candidate.name == request.name
                && candidate.email == request.email
                && candidate.phoneNumber == "+49123456789"
                && candidate.password == "merged-hash"
        }) } answers { firstArg() }
        every { emailVerificationTokenRepository.save(any()) } returnsArgument 0

        val (seller, _) = service.createSellerWithToken(request)

        assertEquals(emailOwner.sellerId, seller.sellerId)
        assertEquals("+49123456789", seller.phoneNumber)
        verify(exactly = 1) { sellerRepository.deleteById(phoneOwner.sellerId!!) }
        verify(exactly = 1) { whatsAppLoginSessionRepository.deleteBySellerId(emailOwner.sellerId!!) }
        verify(exactly = 1) { whatsAppLoginSessionRepository.deleteBySellerId(phoneOwner.sellerId!!) }
    }

    @Test
    fun `createSeller rejects normalized duplicate phone numbers`() {
        val request = SellerRequestDTO(
            name = "New Seller",
            email = "new@constructor.university",
            phoneNumber = "49123456789",
            password = "Password1!"
        )

        every { sellerRepository.existsByEmail(request.email) } returns false
        every { sellerRepository.findByPhoneNumber("+49123456789") } returns buildSeller(
            sellerId = 91L,
            email = "existing@constructor.university",
            phoneNumber = "+49123456789"
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.createSeller(request)
        }

        assertEquals("Phone number already registered: ${request.phoneNumber}", error.message)
    }

    @Test
    fun `getSellerByPhone returns null for unverified seller`() {
        every { sellerRepository.findByPhoneNumber("+49123456789") } returns buildSeller(
            sellerId = 33L,
            email = "pending@constructor.university",
            phoneNumber = "+49123456789",
            isEnabled = false
        )

        val result = service.getSellerByPhone("49123456789")

        assertNull(result)
    }

    @Test
    fun `deleteOwnAccount removes whatsapp sessions before deleting seller`() {
        val seller = buildSeller()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(seller.email, "password")

        every { sellerRepository.findByEmail(seller.email) } returns seller
        every { passwordEncoder.matches("password", seller.password) } returns true
        every { emailVerificationTokenRepository.deleteBySellerSellerId(seller.sellerId!!) } just Runs
        every { passwordResetTokenRepository.deleteBySellerSellerId(seller.sellerId!!) } just Runs
        every { favouriteRepository.deleteBySellerSellerId(seller.sellerId!!) } just Runs
        every { reviewRepository.deleteByReviewerSellerIdOrRevieweeSellerId(seller.sellerId!!, seller.sellerId!!) } just Runs
        every { whatsAppLoginSessionRepository.deleteBySellerId(seller.sellerId!!) } just Runs
        every { postRepository.clearBuyerReferences(seller.sellerId!!) } returns 0
        every { postRepository.findBySellerSellerId(seller.sellerId!!) } returns emptyList()
        every { postRepository.deleteAll(emptyList()) } just Runs
        every { sellerRepository.delete(seller) } just Runs

        service.deleteOwnAccount("password")

        verify(exactly = 1) { whatsAppLoginSessionRepository.deleteBySellerId(seller.sellerId!!) }
        verify(exactly = 1) { sellerRepository.delete(seller) }
    }

    @Test
    fun `deleteSeller removes whatsapp sessions before deleting seller row`() {
        val sellerId = 42L

        every { sellerRepository.existsById(sellerId) } returns true
        every { emailVerificationTokenRepository.deleteBySellerSellerId(sellerId) } just Runs
        every { passwordResetTokenRepository.deleteBySellerSellerId(sellerId) } just Runs
        every { favouriteRepository.deleteBySellerSellerId(sellerId) } just Runs
        every { reviewRepository.deleteByReviewerSellerIdOrRevieweeSellerId(sellerId, sellerId) } just Runs
        every { whatsAppLoginSessionRepository.deleteBySellerId(sellerId) } just Runs
        every { postRepository.clearBuyerReferences(sellerId) } returns 0
        every { postRepository.findBySellerSellerId(sellerId) } returns emptyList()
        every { postRepository.deleteAll(emptyList()) } just Runs
        every { sellerRepository.deleteById(sellerId) } just Runs

        service.deleteSeller(sellerId)

        verify(exactly = 1) { whatsAppLoginSessionRepository.deleteBySellerId(sellerId) }
        verify(exactly = 1) { sellerRepository.deleteById(sellerId) }
    }

    private fun stubSellerDependencyCleanup(sellerId: Long) {
        every { emailVerificationTokenRepository.deleteBySellerSellerId(sellerId) } just Runs
        every { passwordResetTokenRepository.deleteBySellerSellerId(sellerId) } just Runs
        every { favouriteRepository.deleteBySellerSellerId(sellerId) } just Runs
        every { reviewRepository.deleteByReviewerSellerIdOrRevieweeSellerId(sellerId, sellerId) } just Runs
        every { whatsAppLoginSessionRepository.deleteBySellerId(sellerId) } just Runs
        every { postRepository.clearBuyerReferences(sellerId) } returns 0
        every { postRepository.findBySellerSellerId(sellerId) } returns emptyList()
        every { postRepository.deleteAll(emptyList()) } just Runs
    }

    private fun buildSeller(
        sellerId: Long = 42L,
        name: String = "Test Seller",
        email: String = "seller@example.com",
        phoneNumber: String = "+49123456789",
        password: String = "hashed-password",
        role: Role = Role.SELLER,
        isEnabled: Boolean = true
    ) = Seller(
        sellerId = sellerId,
        name = name,
        email = email,
        phoneNumber = phoneNumber,
        password = password,
        role = role,
        isEnabled = isEnabled
    )
}
