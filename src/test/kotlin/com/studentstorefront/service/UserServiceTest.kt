package com.studentstorefront.service

import com.studentstorefront.dto.request.UserRequestDTO
import com.studentstorefront.entity.EmailVerificationToken
import com.studentstorefront.entity.Post
import com.studentstorefront.exception.ConflictException
import com.studentstorefront.entity.User
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.EmailVerificationTokenRepository
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PasswordResetTokenRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.ReviewRepository
import com.studentstorefront.repository.UserRepository
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
import java.time.LocalDateTime
import java.time.ZoneOffset

class UserServiceTest {

    private val sellerRepository: UserRepository = mockk()
    private val postRepository: PostRepository = mockk()
    private val favouriteRepository: FavouriteRepository = mockk()
    private val reviewRepository: ReviewRepository = mockk()
    private val passwordEncoder: PasswordEncoder = mockk()
    private val emailVerificationTokenRepository: EmailVerificationTokenRepository = mockk()
    private val passwordResetTokenRepository: PasswordResetTokenRepository = mockk()
    private val whatsAppLoginSessionRepository: WhatsAppLoginSessionRepository = mockk()

    private val service = UserService(
        userRepository = sellerRepository,
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
    fun `createUserWithToken releases an abandoned pending phone reservation`() {
        val abandoned = buildSeller(
            userId = 7L,
            email = "wrong@constructor.university",
            phoneNumber = "+49123456789",
            password = "old-hash",
            isEnabled = false
        )
        val request = UserRequestDTO(
            name = "Updated User",
            email = "correct@constructor.university",
            phoneNumber = "49123456789",
            password = "Password1!"
        )

        every { sellerRepository.findByEmail(request.email) } returns null
        every { sellerRepository.findByPhoneNumber("+49123456789") } returns abandoned
        every { emailVerificationTokenRepository.findTopByUserEmailAndUsedFalseOrderByCreatedAtDesc(abandoned.email) } returns null
        every { passwordEncoder.encode(request.password) } returns "new-hash"
        every { passwordEncoder.encode(match { it != request.password }) } returns "code-hash"
        stubSellerDependencyCleanup(abandoned.userId!!)
        every { sellerRepository.deleteById(abandoned.userId!!) } just Runs
        every { sellerRepository.flush() } just Runs
        every { sellerRepository.save(match { candidate ->
            candidate.userId == null
                && candidate.name == request.name
                && candidate.email == request.email
                && candidate.phoneNumber == "+49123456789"
                && candidate.password == "new-hash"
        }) } answers { firstArg<User>().copy(userId = 99L) }
        every { emailVerificationTokenRepository.save(any()) } returnsArgument 0

        val (user, _) = service.createUserWithToken(request)

        assertEquals(99L, user.userId)
        assertEquals(request.email, user.email)
        verify(exactly = 1) { sellerRepository.deleteById(abandoned.userId!!) }
        verify(exactly = 1) { emailVerificationTokenRepository.save(any()) }
    }

    @Test
    fun `createUserWithToken does not take over a pending account whose code is still live`() {
        val pending = buildSeller(userId = 7L, email = "first@constructor.university", isEnabled = false)
        val request = UserRequestDTO(
            name = "Second User",
            email = "second@constructor.university",
            phoneNumber = pending.phoneNumber,
            password = "Password1!"
        )
        every { sellerRepository.findByEmail(request.email) } returns null
        every { sellerRepository.findByPhoneNumber(pending.phoneNumber) } returns pending
        every { emailVerificationTokenRepository.findTopByUserEmailAndUsedFalseOrderByCreatedAtDesc(pending.email) } returns
            EmailVerificationToken(
                codeHash = "hash",
                user = pending,
                expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)
            )

        assertThrows(ConflictException::class.java) { service.createUserWithToken(request) }

        verify(exactly = 0) { sellerRepository.deleteById(any()) }
        verify(exactly = 0) { sellerRepository.save(any()) }
    }

    @Test
    fun `register does not reveal that a verified account already uses the email`() {
        val existing = buildSeller(userId = 5L, email = "taken@constructor.university", isEnabled = true)
        val request = UserRequestDTO(
            name = "Someone",
            email = "Taken@constructor.university",
            phoneNumber = "49111222333",
            password = "Password1!"
        )
        every { sellerRepository.findByEmail("taken@constructor.university") } returns existing

        assertEquals("taken@constructor.university", service.register(request))

        verify(exactly = 0) { sellerRepository.save(any()) }
        verify(exactly = 0) { emailVerificationTokenRepository.save(any()) }
    }

    @Test
    fun `createUserWithToken merges separate unverified email and phone reservations`() {
        val emailOwner = buildSeller(
            userId = 11L,
            email = "correct@constructor.university",
            phoneNumber = "+49111000000",
            password = "old-email-hash",
            isEnabled = false
        )
        val phoneOwner = buildSeller(
            userId = 12L,
            email = "wrong@constructor.university",
            phoneNumber = "+49123456789",
            password = "old-phone-hash",
            isEnabled = false
        )
        val request = UserRequestDTO(
            name = "Merged User",
            email = "correct@constructor.university",
            phoneNumber = "49123456789",
            password = "Password1!"
        )

        every { sellerRepository.findByEmail(request.email) } returns emailOwner
        every { sellerRepository.findByPhoneNumber("+49123456789") } returns phoneOwner
        every { passwordEncoder.encode(request.password) } returns "merged-hash"
        every { passwordEncoder.encode(match { it != request.password }) } returns "code-hash"
        stubSellerDependencyCleanup(emailOwner.userId!!)
        stubSellerDependencyCleanup(phoneOwner.userId!!)
        every { emailVerificationTokenRepository.findTopByUserEmailAndUsedFalseOrderByCreatedAtDesc(phoneOwner.email) } returns null
        every { sellerRepository.deleteById(phoneOwner.userId!!) } just Runs
        every { sellerRepository.flush() } just Runs
        every { sellerRepository.save(match { candidate ->
            candidate.userId == emailOwner.userId
                && candidate.name == request.name
                && candidate.email == request.email
                && candidate.phoneNumber == "+49123456789"
                && candidate.password == "merged-hash"
        }) } answers { firstArg() }
        every { emailVerificationTokenRepository.save(any()) } returnsArgument 0

        val (user, _) = service.createUserWithToken(request)

        assertEquals(emailOwner.userId, user.userId)
        assertEquals("+49123456789", user.phoneNumber)
        verify(exactly = 1) { sellerRepository.deleteById(phoneOwner.userId!!) }
        verify(exactly = 1) { whatsAppLoginSessionRepository.deleteByUserId(emailOwner.userId!!) }
        verify(exactly = 1) { whatsAppLoginSessionRepository.deleteByUserId(phoneOwner.userId!!) }
    }

    @Test
    fun `createUser rejects normalized duplicate phone numbers`() {
        val request = UserRequestDTO(
            name = "New User",
            email = "new@constructor.university",
            phoneNumber = "49123456789",
            password = "Password1!"
        )

        every { sellerRepository.existsByEmail(request.email) } returns false
        every { sellerRepository.findByPhoneNumber("+49123456789") } returns buildSeller(
            userId = 91L,
            email = "existing@constructor.university",
            phoneNumber = "+49123456789"
        )

        val error = assertThrows(ConflictException::class.java) {
            service.createUser(request)
        }

        assertEquals(UserService.PHONE_IN_USE_MESSAGE, error.message)
    }

    @Test
    fun `getUserByPhone returns null for unverified user`() {
        every { sellerRepository.findByPhoneNumber("+49123456789") } returns buildSeller(
            userId = 33L,
            email = "pending@constructor.university",
            phoneNumber = "+49123456789",
            isEnabled = false
        )

        val result = service.getUserByPhone("49123456789")

        assertNull(result)
    }

    @Test
    fun `deleteOwnAccount removes whatsapp sessions before deleting user`() {
        val user = buildSeller()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user.email, "password")

        every { sellerRepository.findByEmail(user.email) } returns user
        every { passwordEncoder.matches("password", user.password) } returns true
        every { emailVerificationTokenRepository.deleteByUserUserId(user.userId!!) } just Runs
        every { passwordResetTokenRepository.deleteByUserUserId(user.userId!!) } just Runs
        every { favouriteRepository.deleteByUserUserId(user.userId!!) } just Runs
        every { reviewRepository.deleteByReviewerUserIdOrRevieweeUserId(user.userId!!, user.userId!!) } just Runs
        every { whatsAppLoginSessionRepository.deleteByUserId(user.userId!!) } just Runs
        every { postRepository.clearBuyerReferences(user.userId!!) } returns 0
        every { postRepository.findByUserUserId(user.userId!!) } returns emptyList()
        every { postRepository.deleteAll(emptyList()) } just Runs
        every { sellerRepository.delete(user) } just Runs

        service.deleteOwnAccount("password")

        verify(exactly = 1) { whatsAppLoginSessionRepository.deleteByUserId(user.userId!!) }
        verify(exactly = 1) { sellerRepository.delete(user) }
    }

    @Test
    fun `deleteUser removes whatsapp sessions before deleting user row`() {
        val userId = 42L

        every { sellerRepository.existsById(userId) } returns true
        every { emailVerificationTokenRepository.deleteByUserUserId(userId) } just Runs
        every { passwordResetTokenRepository.deleteByUserUserId(userId) } just Runs
        every { favouriteRepository.deleteByUserUserId(userId) } just Runs
        every { reviewRepository.deleteByReviewerUserIdOrRevieweeUserId(userId, userId) } just Runs
        every { whatsAppLoginSessionRepository.deleteByUserId(userId) } just Runs
        every { postRepository.clearBuyerReferences(userId) } returns 0
        every { postRepository.findByUserUserId(userId) } returns emptyList()
        every { postRepository.deleteAll(emptyList()) } just Runs
        every { sellerRepository.deleteById(userId) } just Runs

        service.deleteUser(userId)

        verify(exactly = 1) { whatsAppLoginSessionRepository.deleteByUserId(userId) }
        verify(exactly = 1) { sellerRepository.deleteById(userId) }
    }

    @Test
    fun `deleteUser removes favourites and reviews for owned posts before deleting them`() {
        val user = buildSeller(userId = 42L)
        val posts = listOf(
            Post(postId = 13L, user = user),
            Post(postId = 14L, user = user)
        )

        every { sellerRepository.existsById(user.userId!!) } returns true
        stubSellerDependencyCleanup(user.userId!!, posts)
        every { sellerRepository.deleteById(user.userId!!) } just Runs

        service.deleteUser(user.userId!!)

        verify(exactly = 1) { favouriteRepository.deleteByPostPostId(13L) }
        verify(exactly = 1) { favouriteRepository.deleteByPostPostId(14L) }
        verify(exactly = 1) { reviewRepository.deleteBySalePostPostId(13L) }
        verify(exactly = 1) { reviewRepository.deleteBySalePostPostId(14L) }
        verify(exactly = 1) { postRepository.deleteAll(posts) }
    }

    @Test
    fun `pending registration cannot merge away a disabled established account`() {
        val emailOwner = buildSeller(userId = 11L, isEnabled = false)
        val phoneOwner = buildSeller(userId = 12L, isEnabled = false).copy(registrationPending = false)
        val request = UserRequestDTO(
            name = "New User",
            email = emailOwner.email,
            phoneNumber = phoneOwner.phoneNumber,
            password = "Password1!"
        )
        every { sellerRepository.findByEmail(request.email) } returns emailOwner
        every { sellerRepository.findByPhoneNumber(request.phoneNumber) } returns phoneOwner

        assertThrows(ConflictException::class.java) { service.createUserWithToken(request) }

        verify(exactly = 0) { sellerRepository.deleteById(any()) }
        verify(exactly = 0) { postRepository.deleteAll(any<List<Post>>()) }
    }

    private fun stubSellerDependencyCleanup(userId: Long, ownedPosts: List<Post> = emptyList()) {
        every { emailVerificationTokenRepository.deleteByUserUserId(userId) } just Runs
        every { passwordResetTokenRepository.deleteByUserUserId(userId) } just Runs
        every { favouriteRepository.deleteByUserUserId(userId) } just Runs
        every { reviewRepository.deleteByReviewerUserIdOrRevieweeUserId(userId, userId) } just Runs
        every { whatsAppLoginSessionRepository.deleteByUserId(userId) } just Runs
        every { postRepository.clearBuyerReferences(userId) } returns 0
        ownedPosts.mapNotNull { it.postId }.forEach { postId ->
            every { favouriteRepository.deleteByPostPostId(postId) } just Runs
            every { reviewRepository.deleteBySalePostPostId(postId) } just Runs
        }
        every { postRepository.findByUserUserId(userId) } returns ownedPosts
        every { postRepository.deleteAll(ownedPosts) } just Runs
    }

    private fun buildSeller(
        userId: Long = 42L,
        name: String = "Test User",
        email: String = "user@example.com",
        phoneNumber: String = "+49123456789",
        password: String = "hashed-password",
        role: Role = Role.USER,
        isEnabled: Boolean = true
    ) = User(
        userId = userId,
        name = name,
        email = email,
        phoneNumber = phoneNumber,
        password = password,
        role = role,
        isEnabled = isEnabled
    )
}
