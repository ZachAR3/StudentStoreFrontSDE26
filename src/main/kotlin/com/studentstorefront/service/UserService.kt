package com.studentstorefront.service

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
import com.studentstorefront.repository.UserRepository
import com.studentstorefront.repository.WhatsAppLoginSessionRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom

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
    private val verificationCodeRng = SecureRandom()

    private fun generateVerificationCode(): String = (verificationCodeRng.nextInt(900000) + 100000).toString()

    fun createUserWithToken(request: UserRequestDTO): Pair<UserResponseDTO, String> {
        val normalizedPhone = normalizePhone(request.phoneNumber)
        val user = resolveRegistrationUser(request, normalizedPhone)
        val rawCode = generateVerificationCode()
        val token = EmailVerificationToken(user = user, codeHash = passwordEncoder.encode(rawCode)!!)
        emailVerificationTokenRepository.save(token)
        return mapToPrivateResponseDTO(user) to rawCode
    }

    fun createUser(sellerRequestDTO: UserRequestDTO): UserResponseDTO {
        val normalizedPhone = normalizePhone(sellerRequestDTO.phoneNumber)

        // Check if email already exists
        if (userRepository.existsByEmail(sellerRequestDTO.email)) {
            throw IllegalArgumentException("Email already exists: ${sellerRequestDTO.email}")
        }

        // Check if phone number already exists
        if (userRepository.findByPhoneNumber(normalizedPhone) != null) {
            throw IllegalArgumentException("Phone number already registered: ${sellerRequestDTO.phoneNumber}")
        }

        val user = createUserEntity(sellerRequestDTO, normalizedPhone)
        val savedUser = userRepository.save(user)
        return mapToPrivateResponseDTO(savedUser)
    }

    @Transactional(readOnly = true)
    fun getAllUsers(pageable: Pageable): Page<UserResponseDTO> {
        return userRepository.findAll(pageable).map { mapToPrivateResponseDTO(it) }
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
        val user = userRepository.findByEmail(email)
            ?: throw IllegalArgumentException("User not found with email: $email")
        return mapToPrivateResponseDTO(user)
    }

    @Transactional(readOnly = true)
    fun getUserByPhone(phone: String): UserResponseDTO? {
        val user = userRepository.findByPhoneNumber(normalizePhone(phone))
            ?.takeIf { it.isEnabled }
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
            ?.let { userRepository.findByEmail(it)?.userId }
        return userRepository.searchEnabledUsersByName(
            escapeLike(normalizedQuery),
            currentUserId,
            pageable
        ).map { mapToPublicResponseDTO(it) }
    }

    fun updateUser(userId: Long, userUpdateDTO: UserUpdateDTO): UserResponseDTO {
        val existingUser = findUserById(userId)
        val current = getCurrentUser()
        if (current.role != Role.ADMIN && existingUser.userId != current.userId) {
            throw AccessDeniedException("You do not have permission to update this account")
        }

        // Check if email is being updated and if it already exists
        userUpdateDTO.email?.let { newEmail ->
            if (newEmail != existingUser.email && userRepository.existsByEmail(newEmail)) {
                throw IllegalArgumentException("Email already exists: $newEmail")
            }
        }

        userUpdateDTO.phoneNumber?.let { newPhone ->
            val normalized = normalizePhone(newPhone)
            if (normalized != existingUser.phoneNumber && userRepository.findByPhoneNumber(normalized) != null) {
                throw IllegalArgumentException("Phone number already registered: $newPhone")
            }
        }

        val updatedUser = updateUserEntity(existingUser, userUpdateDTO)
        val savedUser = userRepository.save(updatedUser)
        return mapToPrivateResponseDTO(savedUser)
    }

    fun deleteUser(userId: Long) {
        if (!userRepository.existsById(userId)) {
            throw IllegalArgumentException("User not found with id: $userId")
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

        // Delete dependent rows before removing posts/account.
        clearUserDependencies(user.userId!!)

        // Delete the user
        userRepository.delete(user)
    }

    // Private helper methods
    private fun getCurrentUser(): User {
        val email = SecurityContextHolder.getContext().authentication?.name
            ?: throw IllegalArgumentException("Not authenticated")
        return userRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Authenticated user not found")
    }

    private fun findUserById(userId: Long): User {
        return userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found with id: $userId") }
    }

    private fun escapeLike(input: String): String =
        input.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private fun normalizePhone(phone: String): String {
        val digits = phone.replace(Regex("\\D"), "")
        return "+$digits"
    }

    private fun resolveRegistrationUser(request: UserRequestDTO, normalizedPhone: String): User {
        val emailOwner = userRepository.findByEmail(request.email)
        val phoneOwner = userRepository.findByPhoneNumber(normalizedPhone)

        if (emailOwner?.isEnabled == true) {
            throw IllegalArgumentException("Email already exists: ${request.email}")
        }

        if (phoneOwner?.isEnabled == true && phoneOwner.userId != emailOwner?.userId) {
            throw IllegalArgumentException("Phone number already registered: ${request.phoneNumber}")
        }

        return when {
            emailOwner == null && phoneOwner == null -> userRepository.save(createUserEntity(request, normalizedPhone))
            emailOwner != null && phoneOwner != null && emailOwner.userId != phoneOwner.userId ->
                recycleMergedUnverifiedAccounts(emailOwner, phoneOwner, request, normalizedPhone)
            else -> recycleUnverifiedSeller(emailOwner ?: phoneOwner!!, request, normalizedPhone)
        }
    }

    private fun recycleMergedUnverifiedAccounts(
        emailOwner: User,
        phoneOwner: User,
        request: UserRequestDTO,
        normalizedPhone: String
    ): User {
        clearUserDependencies(phoneOwner.userId!!)
        userRepository.deleteById(phoneOwner.userId!!)
        return recycleUnverifiedSeller(emailOwner, request, normalizedPhone)
    }

    private fun recycleUnverifiedSeller(
        user: User,
        request: UserRequestDTO,
        normalizedPhone: String
    ): User {
        clearUserDependencies(user.userId!!)
        return userRepository.save(
            user.copy(
                name = request.name,
                email = request.email,
                phoneNumber = normalizedPhone,
                password = passwordEncoder.encode(request.password)!!
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
        postRepository.deleteAll(ownedPosts)
    }

    private fun createUserEntity(userRequestDTO: UserRequestDTO, normalizedPhone: String): User {
        return User(
            name = userRequestDTO.name,
            email = userRequestDTO.email,
            phoneNumber = normalizedPhone,
            password = passwordEncoder.encode(userRequestDTO.password)!!
        )
    }

    private fun updateUserEntity(existingUser: User, userUpdateDTO: UserUpdateDTO): User {
        return existingUser.copy(
            name = userUpdateDTO.name ?: existingUser.name,
            email = userUpdateDTO.email ?: existingUser.email,
            phoneNumber = userUpdateDTO.phoneNumber?.let { normalizePhone(it) } ?: existingUser.phoneNumber,
            password = userUpdateDTO.password?.let { passwordEncoder.encode(it) } ?: existingUser.password
        )
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
}
