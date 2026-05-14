package com.studentstorefront.service

import com.studentstorefront.dto.request.PostRequestDTO
import com.studentstorefront.dto.request.MarkSoldRequestDTO
import com.studentstorefront.dto.response.PostResponseDTO
import com.studentstorefront.dto.response.PublicUserResponseDTO
import com.studentstorefront.dto.update.PostImageReferenceDTO
import com.studentstorefront.dto.update.PostUpdateDTO
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import com.studentstorefront.enums.Role
import com.studentstorefront.entity.Post
import com.studentstorefront.entity.PostMedia
import com.studentstorefront.entity.Sale
import com.studentstorefront.entity.User
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PostMediaRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.ReviewRepository
import com.studentstorefront.repository.SaleRepository
import com.studentstorefront.repository.UserRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime


@Service
@Transactional
class PostService(
    private val postRepository: PostRepository,
    private val userRepository: UserRepository,
    private val postMediaRepository: PostMediaRepository,
    private val favouriteRepository: FavouriteRepository,
    private val reviewRepository: ReviewRepository,
    private val saleRepository: SaleRepository,
    private val cloudinaryService: CloudinaryService,
    private val botNotificationService: BotNotificationService
) {

    fun createPost(postRequestDTO: PostRequestDTO): PostResponseDTO {
        val current = getCurrentUser()
        val userId = if (current.role == Role.ADMIN) postRequestDTO.userId ?: current.userId!! else current.userId!!
        val user = findUserById(userId)
        val post = createPostEntity(postRequestDTO, user)
        val savedPost = postRepository.save(post)
        savePostMedia(savedPost, postRequestDTO.imageUrlList)
        return mapToResponseDTO(savedPost, current.userId)
    }

    fun createPostWithImages(
        postRequestDTO: PostRequestDTO,
        images: List<org.springframework.web.multipart.MultipartFile>,
        coverIndex: Int
    ): PostResponseDTO {
        val current = getCurrentUser()
        val userId = if (current.role == Role.ADMIN) postRequestDTO.userId ?: current.userId!! else current.userId!!
        val user = findUserById(userId)
        val post = createPostEntity(postRequestDTO, user)
        val savedPost = postRepository.save(post)

        val mediaUrls = mutableListOf<String>()

        images.forEachIndexed { index, file ->
            val url = cloudinaryService.uploadImage(file)
            if (url != null) {
                val postMedia = PostMedia(
                    post = savedPost,
                    mediaUrl = url,
                    displayOrder = index,
                    isCover = (index == coverIndex)
                )
                postMediaRepository.save(postMedia)
                mediaUrls.add(url)
            }
        }

        if (mediaUrls.isEmpty() && images.isNotEmpty()) {
            throw RuntimeException("Failed to upload images")
        }

        return mapToResponseDTO(savedPost, current.userId)
    }

    fun createPostAsBot(postRequestDTO: PostRequestDTO): PostResponseDTO {
        val userId = postRequestDTO.userId
            ?: throw IllegalArgumentException("userId is required for bot post creation")
        val user = findUserById(userId)
        val post = createPostEntity(postRequestDTO, user)
        val savedPost = postRepository.save(post)
        savePostMedia(savedPost, postRequestDTO.imageUrlList)
        return mapToResponseDTO(savedPost)
    }

    @Transactional(readOnly = true)
    fun getAllPosts(pageable: Pageable): Page<PostResponseDTO> {
        val currentUserId = getCurrentUserIdOrNull()
        return postRepository.findAll(pageable).map { mapToResponseDTO(it, currentUserId) }
    }

    @Transactional(readOnly = true)
    fun getPostById(postId: Long): PostResponseDTO {
        val post = findPostById(postId)
        return mapToResponseDTO(post, getCurrentUserIdOrNull())
    }

    @Transactional(readOnly = true)
    fun getPostsByCategory(category: Category, pageable: Pageable): Page<PostResponseDTO> {
        val currentUserId = getCurrentUserIdOrNull()
        return postRepository.findByCategoryAndStatus(category, PostStatus.ACTIVE, pageable).map { mapToResponseDTO(it, currentUserId) }
    }

    @Transactional(readOnly = true)
    fun getPostsByUser(userId: Long, pageable: Pageable): Page<PostResponseDTO> {
        val currentUserId = getCurrentUserIdOrNull()
        return postRepository.findByUserUserId(userId, pageable).map { mapToResponseDTO(it, currentUserId) }
    }

    @Transactional(readOnly = true)
    fun getAvailablePosts(pageable: Pageable): Page<PostResponseDTO> {
        val currentUserId = getCurrentUserIdOrNull()
        return postRepository.findByIsSoldFalseAndStatus(PostStatus.ACTIVE, pageable).map { mapToResponseDTO(it, currentUserId) }
    }

    @Transactional(readOnly = true)
    fun searchAvailablePosts(query: String?, category: Category?, pageable: Pageable): Page<PostResponseDTO> {
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val currentUserId = getCurrentUserIdOrNull()

        val posts = when {
            normalizedQuery == null && category == null -> postRepository.findByIsSoldFalseAndStatus(PostStatus.ACTIVE, pageable)
            normalizedQuery == null && category != null -> postRepository.findByCategoryAndIsSoldFalseAndStatus(category, PostStatus.ACTIVE, pageable)
            else -> postRepository.searchAvailablePosts(escapeLike(normalizedQuery!!), category, pageable)
        }

        return posts.map { mapToResponseDTO(it, currentUserId) }
    }

    fun updatePost(postId: Long, postUpdateDTO: PostUpdateDTO): PostResponseDTO {
        val savedPost = saveUpdatedPost(postId, postUpdateDTO)
        if (postUpdateDTO.imageUrlList != null) {
            postMediaRepository.deleteAll(postMediaRepository.findByPost_postId(postId))
            savePostMedia(savedPost, postUpdateDTO.imageUrlList)

        }
        return mapToResponseDTO(savedPost, getCurrentUserIdOrNull())
    }

    fun updatePostWithImages(
        postId: Long,
        postUpdateDTO: PostUpdateDTO,
        images: List<org.springframework.web.multipart.MultipartFile>,
        coverIndex: Int
    ): PostResponseDTO {
        val savedPost = saveUpdatedPost(postId, postUpdateDTO)

        if (postUpdateDTO.imageOrder != null || images.isNotEmpty()) {
            replacePostMedia(savedPost, postUpdateDTO.imageOrder, images, coverIndex)
        }

        return mapToResponseDTO(savedPost, getCurrentUserIdOrNull())
    }

    fun deletePost(postId: Long) {
        val post = findPostById(postId)
        assertOwnerOrAdmin(post)
        favouriteRepository.deleteByPostPostId(postId)
        reviewRepository.deleteBySalePostPostId(postId)
        postRepository.deleteById(postId)
    }

    fun markAsSold(postId: Long, request: MarkSoldRequestDTO): PostResponseDTO {
        val post = findPostById(postId)
        assertOwnerOrAdmin(post)
        if (post.isSold) {
            throw IllegalArgumentException("Listing is already marked as sold")
        }
        if (post.status != PostStatus.ACTIVE) {
            throw IllegalArgumentException("Only active listings can be marked as sold")
        }
        val buyerUserId = request.buyerUserId ?: throw IllegalArgumentException("Buyer ID is required")
        val buyer = findUserById(buyerUserId)
        if (!buyer.isEnabled) {
            throw IllegalArgumentException("Buyer must be a verified enabled user")
        }
        if (buyer.userId == post.user?.userId) {
            throw IllegalArgumentException("User cannot select themselves as the buyer")
        }

        val soldAt = LocalDateTime.now()
        val updatedPost = post.copy(
            isSold = true,
            buyer = buyer,
            soldAt = soldAt,
            status = PostStatus.SOLD
        )
        val savedPost = postRepository.save(updatedPost)
        saleRepository.save(
            Sale(
                post = savedPost,
                seller = savedPost.user!!,
                buyer = buyer,
                soldAt = soldAt
            )
        )
        botNotificationService.sendSellerReviewRequest(savedPost)
        return mapToResponseDTO(savedPost, getCurrentUserIdOrNull())
    }

    fun renewPost(postId: Long): PostResponseDTO {
        val post = findPostById(postId)
        if (post.isSold || saleRepository.existsByPostPostId(postId)) {
            throw IllegalArgumentException("Sold listings cannot be renewed")
        }
        if (post.status != PostStatus.ARCHIVED) {
            throw IllegalArgumentException("Only archived listings can be renewed")
        }
        val renewed = post.copy(
            status = PostStatus.ACTIVE,
            expiresAt = LocalDateTime.now().plusDays(2),
            reminderSentAt = null
        )
        return mapToResponseDTO(postRepository.save(renewed), getCurrentUserIdOrNull())
    }

    // Private helper methods for cleaner code

    private fun findUserById(userId: Long): User {
        return userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found with id: $userId") }
    }

    private fun findPostById(postId: Long): Post {
        return postRepository.findById(postId)
            .orElseThrow { IllegalArgumentException("Post not found with id: $postId") }
    }

    private fun createPostEntity(postRequestDTO: PostRequestDTO, user: User): Post {
        return Post(
            title = postRequestDTO.title,
            price = postRequestDTO.price,
            description = postRequestDTO.description,
            category = postRequestDTO.category,
            isSold = false,
            createdAt = LocalDateTime.now(),
            expiresAt = LocalDateTime.now().plusDays(2),
            user = user
        )
    }

    private fun updatePostEntity(existingPost: Post, postUpdateDTO: PostUpdateDTO): Post {
        return existingPost.copy(
            title = postUpdateDTO.title ?: existingPost.title,
            price = postUpdateDTO.price ?: existingPost.price,
            description = postUpdateDTO.description ?: existingPost.description,
            category = postUpdateDTO.category ?: existingPost.category
        )
    }

    private fun saveUpdatedPost(postId: Long, postUpdateDTO: PostUpdateDTO): Post {
        val existingPost = findPostById(postId)
        assertOwnerOrAdmin(existingPost)
        return postRepository.save(updatePostEntity(existingPost, postUpdateDTO))
    }

    @Transactional(readOnly = true)
    private fun savePostMedia(post: Post, imageUrlList: List<String>?) {
        postMediaRepository.saveAll((imageUrlList ?: emptyList()).mapIndexed { index, mediaUrl ->
            PostMedia(
                post = post,
                mediaUrl = mediaUrl,
                displayOrder = index,
                isCover = index == 0
            )
        })
    }

    private fun replacePostMedia(
        post: Post,
        imageOrder: List<PostImageReferenceDTO>?,
        images: List<org.springframework.web.multipart.MultipartFile>,
        coverIndex: Int
    ) {
        require(coverIndex >= 0) { "Cover index must not be negative" }

        val currentMedia = findOrderedPostMedia(post.postId!!)
        val existingUrls = currentMedia.map { it.mediaUrl }
        val uploadedUrls = images.map { file ->
            cloudinaryService.uploadImage(file) ?: throw RuntimeException("Failed to upload images")
        }
        val orderedUrls = resolveUpdatedMediaUrls(existingUrls, imageOrder, uploadedUrls, coverIndex)
        require(orderedUrls.isNotEmpty()) { "At least one image is required" }

        postMediaRepository.deleteAll(currentMedia)
        postMediaRepository.saveAll(
            orderedUrls.mapIndexed { index, mediaUrl ->
                PostMedia(
                    post = post,
                    mediaUrl = mediaUrl,
                    displayOrder = index,
                    isCover = index == 0
                )
            }
        )
    }

    private fun resolveUpdatedMediaUrls(
        existingUrls: List<String>,
        imageOrder: List<PostImageReferenceDTO>?,
        uploadedUrls: List<String>,
        coverIndex: Int
    ): List<String> {
        val existingSet = existingUrls.toSet()

        val orderedUrls = if (imageOrder.isNullOrEmpty()) {
            existingUrls + uploadedUrls
        } else {
            imageOrder.map { reference ->
                resolveImageReference(reference, existingSet, uploadedUrls)
            }
        }

        require(orderedUrls.size == orderedUrls.distinct().size) { "Duplicate images are not allowed" }
        require(coverIndex < orderedUrls.size) { "Cover index is out of range" }

        return if (coverIndex == 0) orderedUrls else {
            val reordered = orderedUrls.toMutableList()
            val coverUrl = reordered.removeAt(coverIndex)
            reordered.add(0, coverUrl)
            reordered
        }
    }

    private fun resolveImageReference(
        reference: PostImageReferenceDTO,
        existingSet: Set<String>,
        uploadedUrls: List<String>
    ): String {
        return when (reference.kind.lowercase()) {
            "existing" -> {
                val url = reference.url?.takeIf { it in existingSet }
                    ?: throw IllegalArgumentException("Invalid existing image reference")
                url
            }
            "upload" -> {
                val uploadIndex = reference.uploadIndex
                    ?: throw IllegalArgumentException("Upload index is required for uploaded images")
                uploadedUrls.getOrNull(uploadIndex)
                    ?: throw IllegalArgumentException("Invalid uploaded image reference")
            }
            else -> throw IllegalArgumentException("Invalid image reference kind")
        }
    }

    internal fun mapToResponseDTO(post: Post, currentUserId: Long? = null): PostResponseDTO {
        val canViewBuyer = currentUserId != null && (
            currentUserId == post.user?.userId ||
            currentUserId == post.buyer?.userId ||
            isCurrentUserAdmin()
        )
        return PostResponseDTO(
            postId = post.postId!!,
            title = post.title,
            price = post.price,
            mediaUrls = findOrderedPostMedia(post.postId!!).map { it.mediaUrl },
            description = post.description,
            category = post.category,
            isSold = post.isSold,
            status = post.status ?: PostStatus.ACTIVE,
            createdAt = post.createdAt,
            expiresAt = post.expiresAt,
            soldAt = post.soldAt,
            user = mapUserToPublicResponseDTO(post.user!!),
            whatsappPhone = post.user?.phoneNumber?.ifBlank { null },
            buyer = if (canViewBuyer) post.buyer?.let { mapUserToPublicResponseDTO(it) } else null,
            isFavourited = currentUserId != null &&
                favouriteRepository.existsByUserUserIdAndPostPostId(currentUserId, post.postId!!)
        )
    }

    private fun mapUserToPublicResponseDTO(user: User): PublicUserResponseDTO {
        return PublicUserResponseDTO(
            userId = user.userId!!,
            name = user.name
        )
    }

    private fun findOrderedPostMedia(postId: Long): List<PostMedia> =
        postMediaRepository.findByPost_postId(postId)
            .sortedWith(compareBy({ !it.isCover }, { it.displayOrder }))

    private fun escapeLike(input: String): String =
        input.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private fun getCurrentUser(): User {
        val email = SecurityContextHolder.getContext().authentication?.name
            ?: throw IllegalArgumentException("Not authenticated")
        return userRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Authenticated user not found")
    }

    private fun getCurrentUserIdOrNull(): Long? {
        val email = SecurityContextHolder.getContext().authentication?.name ?: return null
        return userRepository.findByEmail(email)?.userId
    }

    private fun isCurrentUserAdmin(): Boolean {
        val email = SecurityContextHolder.getContext().authentication?.name ?: return false
        return userRepository.findByEmail(email)?.role == Role.ADMIN
    }

    private fun assertOwnerOrAdmin(post: Post) {
        val current = getCurrentUser()
        if (current.role != Role.ADMIN && post.user?.userId != current.userId) {
            throw AccessDeniedException("You do not own this post")
        }
    }
}
