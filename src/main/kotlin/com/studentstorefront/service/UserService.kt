package com.studentstorefront.service

import java.time.ZoneOffset
import java.time.LocalDateTime
import com.studentstorefront.exception.ConflictException
import com.studentstorefront.dto.request.UserRequestDTO
import com.studentstorefront.dto.response.PublicUserResponseDTO
import com.studentstorefront.dto.response.UserResponseDTO
import com.studentstorefront.dto.update.UserUpdateDTO
import com.studentstorefront.entity.EmailVerificationToken
import com.studentstorefront.entity.User
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.EmailVerificationTokenRepository
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PasswordResetTokenRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.ReviewRepository
import com.studentstorefront.repository.SaleRepository
import com.studentstorefront.repository.UserRepository
import com.studentstorefront.repository.WhatsAppLoginSessionRepository
import com.studentstorefront.exception.ResourceNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class UserService(
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val favouriteRepository: FavouriteRepository,
    private val reviewRepository: ReviewRepository,
    private val passwordEncoder: PasswordEncoder,
    private val emailVerificationTokenRepository: EmailVerificationTokenRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val whatsAppLoginSessionRepository: WhatsAppLoginSessionRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Autowired(required = false)
    private var emailService: EmailService? = null

    @Autowired(required = false)
    private var notificationOutboxService: NotificationOutboxService? = null

    @Autowired(required = false)
    private var saleRepository: SaleRepository? = null

    @Autowired(required = false)
    private var mediaCleanupService: MediaCleanupService? = null

    fun createUserWithToken(request: UserRequestDTO): Pair<UserResponseDTO, String> {
        val normalizedRequest = request.copy(
            email = IdentityNormalizer.email(request.email),
            phoneNumber = normalizePhone(request.phoneNumber)
        )
        val user = resolveRegistrationUser(normalizedRequest, normalizedRequest.phoneNumber)
        val rawCode = EmailVerificationToken.generateCode()
        val token = EmailVerificationToken(user = user, codeHash = passwordEncoder.encode(rawCode)!!)
        val savedToken = emailVerificationTokenRepository.save(token)
        if (notificationOutboxService != null) {
            notificationOutboxService!!.enqueueVerificationEmail(savedToken.id!!, user.email, rawCode)
        } else {
            AfterCommit.run {
                runCatching { emailService?.sendVerificationEmail(user.email, rawCode) }
                    .onFailure { logger.error("Verification email delivery failed", it) }
            }
        }
        return mapToPrivateResponseDTO(user) to rawCode
    }

    fun createUser(sellerRequestDTO: UserRequestDTO): UserResponseDTO {
        val normalizedEmail = IdentityNormalizer.email(sellerRequestDTO.email)
        val normalizedPhone = normalizePhone(sellerRequestDTO.phoneNumber)

        // Check if email already exists
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw ConflictException("Email already exists")
        }

        // Check if phone number already exists
        if (userRepository.findByPhoneNumber(normalizedPhone) != null) {
            throw ConflictException(PHONE_IN_USE_MESSAGE)
        }

        val user = createUserEntity(
            sellerRequestDTO.copy(email = normalizedEmail),
            normalizedPhone,
            enabled = true
        )
        val savedUser = userRepository.save(user)
        return mapToPrivateResponseDTO(savedUser)
    }

    @Transactional(readOnly = true)
    fun getAllUsers(pageable: Pageable): Page<UserResponseDTO> {
        return userRepository.findAll(safePageable(pageable)).map { mapToPrivateResponseDTO(it) }
    }

    @Transactional(readOnly = true)
    fun getMe(): UserResponseDTO = mapToPrivateResponseDTO(getCurrentUser())

    @Transactional(readOnly = true)
    fun getUserById(userId: Long): PublicUserResponseDTO {
        val user = findUserById(userId)
        return mapToPublicResponseDTO(user)
    }

    @Transactional(readOnly = true)
    fun getUserByEmail(email: String): UserResponseDTO {
        val normalizedEmail = IdentityNormalizer.email(email)
        val user = userRepository.findByEmail(normalizedEmail)
            ?: throw ResourceNotFoundException()
        return mapToPrivateResponseDTO(user)
    }

    @Transactional(readOnly = true)
    fun getUserByPhone(phone: String): UserResponseDTO? {
        val user = userRepository.findByPhoneNumber(normalizePhone(phone))
            ?.takeIf { it.isEnabled && it.phoneVerifiedAt != null }
            ?: return null
        return mapToPrivateResponseDTO(user)
    }

    @Transactional(readOnly = true)
    fun searchUsers(query: String, pageable: Pageable): Page<PublicUserResponseDTO> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 2) {
            return Page.empty(pageable)
        }
        val currentUserId = SecurityContextHolder.getContext().authentication
            ?.name
            ?.let { userRepository.findByEmail(IdentityNormalizer.email(it))?.userId }
        return userRepository.searchEnabledUsersByName(
            escapeLike(normalizedQuery),
            currentUserId,
            safePageable(pageable)
        ).map { mapToPublicResponseDTO(it).copy(emailHint = maskEmail(it.email)) }
    }

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0) return "…"
        val local = email.substring(0, at)
        val masked = if (local.length <= 2) "${local.first()}…" else "${local.first()}…${local.last()}"
        return masked + email.substring(at)
    }

    fun updateUser(userId: Long, userUpdateDTO: UserUpdateDTO): UserResponseDTO {
        val existingUser = findUserById(userId)
        val current = getCurrentUser()
        if (current.role != Role.ADMIN && existingUser.userId != current.userId) {
            throw AccessDeniedException("You do not have permission to update this account")
        }

        val normalizedEmail = userUpdateDTO.email?.let(IdentityNormalizer::email)
        val emailChanged = normalizedEmail != null && normalizedEmail != existingUser.email
        val phoneChanged = userUpdateDTO.phoneNumber?.let(::normalizePhone)?.let { it != existingUser.phoneNumber } == true
        val passwordChanged = !userUpdateDTO.password.isNullOrBlank()

        if (current.userId == existingUser.userId && (emailChanged || phoneChanged || passwordChanged)) {
            val currentPassword = userUpdateDTO.currentPassword
                ?: throw AccessDeniedException("Current password is required for credential changes")
            if (!passwordEncoder.matches(currentPassword, existingUser.password)) {
                throw AccessDeniedException("Current password is incorrect")
            }
        }

        if (emailChanged) {
            // Checked when the change is requested, not when the code is entered (DB-10).
            if (userRepository.existsByEmail(normalizedEmail!!) || userRepository.findByPendingEmail(normalizedEmail) != null) {
                throw ConflictException("Email already exists")
            }
            requestEmailChange(existingUser, normalizedEmail)
        }

        userUpdateDTO.phoneNumber?.let { newPhone ->
            val normalized = normalizePhone(newPhone)
            if (normalized != existingUser.phoneNumber && userRepository.findByPhoneNumber(normalized) != null) {
                throw ConflictException(PHONE_IN_USE_MESSAGE)
            }
        }

        val updatedUser = updateUserEntity(existingUser, userUpdateDTO, normalizedEmail, emailChanged, phoneChanged, passwordChanged)
        val savedUser = userRepository.save(updatedUser)
        return mapToPrivateResponseDTO(savedUser)
    }

    fun deleteUser(userId: Long) {
        if (!userRepository.existsById(userId)) {
            throw ResourceNotFoundException()
        }
        val user = if (saleRepository != null) userRepository.findById(userId).orElse(null) else null
        user?.let(::enqueueAccountDeletionCleanup)
        if (user != null && hasSales(userId)) {
            anonymizeUser(user)
            return
        }
        clearUserDependencies(userId)
        userRepository.deleteById(userId)
    }

    /**
     * Allows the currently authenticated user to delete their own account.
     * Requires password re-entry for safety. Deletes all associated posts first.
     */
    fun deleteOwnAccount(password: String) {
        val user = getCurrentUser()

        // Verify password
        if (!passwordEncoder.matches(password, user.password)) {
            throw AccessDeniedException("Incorrect password")
        }

        enqueueAccountDeletionCleanup(user)
        if (hasSales(user.userId!!)) {
            anonymizeUser(user)
            return
        }

        // Delete dependent rows before removing posts/account.
        clearUserDependencies(user.userId!!)

        // Delete the user
        userRepository.delete(user)
    }

    // Private helper methods
    private fun getCurrentUser(): User {
        val email = SecurityContextHolder.getContext().authentication?.name
            ?: throw IllegalArgumentException("Not authenticated")
        return userRepository.findByEmail(IdentityNormalizer.email(email))
            ?: throw ResourceNotFoundException()
    }

    private fun findUserById(userId: Long): User {
        return userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException() }
    }

    private fun escapeLike(input: String): String =
        input.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private fun safePageable(pageable: Pageable): Pageable {
        val allowed = setOf("userId", "name", "createdAt", "isEnabled")
        val requested = pageable.sort.toList()
        if (requested.any { it.property !in allowed }) {
            throw IllegalArgumentException("Unsupported sort field")
        }
        val orders = if (requested.isEmpty()) {
            listOf(Sort.Order.asc("name"), Sort.Order.asc("userId"))
        } else if (requested.none { it.property == "userId" }) {
            requested + Sort.Order.asc("userId")
        } else {
            requested
        }
        return PageRequest.of(
            pageable.pageNumber,
            pageable.pageSize.coerceIn(1, MAX_PAGE_SIZE),
            Sort.by(orders)
        )
    }

    private fun normalizePhone(phone: String): String {
        return IdentityNormalizer.phone(phone)
    }

    /**
     * Registration entry point for the public API. It does not reveal whether
     * the email already belongs to a verified account (BE-09): in that case
     * nothing is changed and the caller gets the same "check your inbox" reply.
     */
    fun register(request: UserRequestDTO): String {
        val email = IdentityNormalizer.email(request.email)
        if (userRepository.findByEmail(email)?.isEnabled == true) {
            logger.info("Registration attempted for an existing verified account")
            return email
        }
        return createUserWithToken(request).first.email
    }

    /**
     * Picks the row a registration writes to.
     *
     * A pending account can be recycled through its own email (only the inbox
     * owner can finish verification). A pending account found through a
     * different identifier, the phone, is never taken over while its
     * verification code is still live, and established accounts are never
     * touched (BE-03).
     */
    private fun resolveRegistrationUser(request: UserRequestDTO, normalizedPhone: String): User {
        val emailOwner = userRepository.findByEmail(request.email)
        val phoneOwner = userRepository.findByPhoneNumber(normalizedPhone)
            ?.takeIf { it.userId != emailOwner?.userId }

        if (emailOwner?.isEnabled == true) {
            throw ConflictException("Email already exists")
        }
        if (emailOwner != null && !emailOwner.registrationPending) {
            throw IllegalArgumentException("This account cannot be recycled")
        }
        if (phoneOwner != null) {
            if (phoneOwner.isEnabled || !phoneOwner.registrationPending || hasLiveVerificationCode(phoneOwner)) {
                throw ConflictException(PHONE_IN_USE_MESSAGE)
            }
            // An abandoned pending registration releases the number.
            clearUserDependencies(phoneOwner.userId!!)
            userRepository.deleteById(phoneOwner.userId!!)
            userRepository.flush()
        }

        return if (emailOwner == null) {
            userRepository.save(createUserEntity(request, normalizedPhone))
        } else {
            recycleUnverifiedSeller(emailOwner, request, normalizedPhone)
        }
    }

    private fun hasLiveVerificationCode(user: User): Boolean =
        emailVerificationTokenRepository.findTopByUserEmailAndUsedFalseOrderByCreatedAtDesc(user.email)
            ?.expiresAt?.isAfter(LocalDateTime.now(ZoneOffset.UTC)) == true

    private fun recycleUnverifiedSeller(
        user: User,
        request: UserRequestDTO,
        normalizedPhone: String
    ): User {
        if (user.registrationPending.not()) {
            throw IllegalArgumentException("This account cannot be recycled")
        }
        clearUserDependencies(user.userId!!)
        return userRepository.save(
            user.copy(
                name = request.name,
                email = IdentityNormalizer.email(request.email),
                phoneNumber = normalizedPhone,
                password = passwordEncoder.encode(request.password)!!,
                registrationPending = true,
                pendingEmail = null,
                tokenVersion = user.tokenVersion + 1
            )
        )
    }

    private fun clearUserDependencies(userId: Long) {
        emailVerificationTokenRepository.deleteByUserUserId(userId)
        passwordResetTokenRepository.deleteByUserUserId(userId)
        favouriteRepository.deleteByUserUserId(userId)
        reviewRepository.deleteByReviewerUserIdOrRevieweeUserId(userId, userId)
        whatsAppLoginSessionRepository.deleteByUserId(userId)
        postRepository.clearBuyerReferences(userId)
        val ownedPosts = postRepository.findByUserUserId(userId)
        ownedPosts.mapNotNull { it.postId }.forEach { postId ->
            favouriteRepository.deleteByPostPostId(postId)
            reviewRepository.deleteBySalePostPostId(postId)
        }
        mediaCleanupService?.scheduleForPosts(ownedPosts)
        postRepository.deleteAll(ownedPosts)
    }

    private fun createUserEntity(
        userRequestDTO: UserRequestDTO,
        normalizedPhone: String,
        enabled: Boolean = false
    ): User {
        return User(
            name = userRequestDTO.name,
            email = IdentityNormalizer.email(userRequestDTO.email),
            phoneNumber = normalizedPhone,
            password = passwordEncoder.encode(userRequestDTO.password)!!,
            isEnabled = enabled,
            registrationPending = !enabled
        )
    }

    private fun updateUserEntity(
        existingUser: User,
        userUpdateDTO: UserUpdateDTO,
        normalizedEmail: String?,
        emailChanged: Boolean,
        phoneChanged: Boolean,
        passwordChanged: Boolean
    ): User {
        return existingUser.copy(
            name = userUpdateDTO.name ?: existingUser.name,
            email = if (emailChanged) existingUser.email else normalizedEmail ?: existingUser.email,
            phoneNumber = userUpdateDTO.phoneNumber?.let { normalizePhone(it) } ?: existingUser.phoneNumber,
            password = userUpdateDTO.password?.takeIf { it.isNotBlank() }?.let { passwordEncoder.encode(it) } ?: existingUser.password,
            tokenVersion = if (phoneChanged || passwordChanged) existingUser.tokenVersion + 1 else existingUser.tokenVersion,
            pendingEmail = if (emailChanged) normalizedEmail else existingUser.pendingEmail,
            phoneVerifiedAt = if (phoneChanged) null else existingUser.phoneVerifiedAt
        )
    }

    private fun requestEmailChange(user: User, targetEmail: String) {
        emailVerificationTokenRepository.deleteByUserUserId(user.userId!!)
        val code = EmailVerificationToken.generateCode()
        val token = emailVerificationTokenRepository.save(
            EmailVerificationToken(
                user = user,
                codeHash = passwordEncoder.encode(code)!!,
                targetEmail = targetEmail
            )
        )
        if (notificationOutboxService != null) {
            notificationOutboxService!!.enqueueVerificationEmail(token.id!!, targetEmail, code)
        } else {
            AfterCommit.run {
                runCatching { emailService?.sendVerificationEmail(targetEmail, code) }
                    .onFailure { logger.error("Verification email delivery failed", it) }
            }
        }
    }

    private fun hasSales(userId: Long): Boolean =
        saleRepository?.existsBySellerUserIdOrBuyerUserId(userId, userId) == true

    /**
     * Used when the account has sale history that must stay intact for the
     * other party. Unsold listings and their media are removed so nothing
     * stays public under "Deleted user" (BE-04).
     */
    private fun anonymizeUser(user: User) {
        clearUserCredentials(user.userId!!)
        val unsoldPosts = postRepository.findByUserUserId(user.userId!!)
            .filter { post -> !post.isSold && saleRepository?.existsByPostPostId(post.postId!!) != true }
        unsoldPosts.mapNotNull { it.postId }.forEach(favouriteRepository::deleteByPostPostId)
        mediaCleanupService?.scheduleForPosts(unsoldPosts)
        postRepository.deleteAll(unsoldPosts)
        userRepository.save(
            user.copy(
                name = "Deleted user",
                email = "deleted-${user.userId}@invalid.local",
                phoneNumber = "+999${user.userId}",
                password = passwordEncoder.encode(java.util.UUID.randomUUID().toString())!!,
                isEnabled = false,
                registrationPending = false,
                pendingEmail = null,
                phoneVerifiedAt = null,
                tokenVersion = user.tokenVersion + 1
            )
        )
    }

    private fun clearUserCredentials(userId: Long) {
        emailVerificationTokenRepository.deleteByUserUserId(userId)
        passwordResetTokenRepository.deleteByUserUserId(userId)
        whatsAppLoginSessionRepository.deleteByUserId(userId)
        favouriteRepository.deleteByUserUserId(userId)
    }

    private fun enqueueAccountDeletionCleanup(user: User) {
        val phone = user.phoneNumber.takeIf { it.isNotBlank() } ?: return
        notificationOutboxService?.enqueueAccountDeleted(user.userId!!, phone)
    }

    fun mapToPrivateResponseDTO(user: User): UserResponseDTO {
        return UserResponseDTO(
            userId = user.userId!!,
            name = user.name,
            email = user.email,
            phoneNumber = user.phoneNumber
        )
    }

    fun mapToPublicResponseDTO(user: User): PublicUserResponseDTO {
        return PublicUserResponseDTO(
            userId = user.userId!!,
            name = user.name
        )
    }

    companion object {
        private const val MAX_PAGE_SIZE = 50
        const val PHONE_IN_USE_MESSAGE = "This phone number is already linked to an account"
    }
}
