package com.studentstorefront.service

import com.studentstorefront.exception.DomainValidationException
import com.studentstorefront.exception.ConflictException
import com.studentstorefront.scheduler.PostExpirationScheduler
import com.studentstorefront.dto.request.PostRequestDTO
import com.studentstorefront.dto.request.MarkSoldRequestDTO
import com.studentstorefront.dto.response.BotSellerContactDTO
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
import com.studentstorefront.exception.ResourceNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.Clock
import java.math.RoundingMode


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
    private val clock: Clock = Clock.systemUTC()
) {

    fun createPost(postRequestDTO: PostRequestDTO): PostResponseDTO {
        val current = getCurrentUser()
        validatePostInput(postRequestDTO)
        val userId = if (current.role == Role.ADMIN) postRequestDTO.userId ?: current.userId!! else current.userId!!
        val user = findUserById(userId)
        val post = createPostEntity(postRequestDTO, user)
        val savedPost = postRepository.save(post)
        savePostMedia(savedPost, postRequestDTO.imageUrlList, postRequestDTO.imageHashList)
        return mapToResponseDTO(savedPost, current.userId)
    }

    fun createPostWithImages(
        postRequestDTO: PostRequestDTO,
        images: List<org.springframework.web.multipart.MultipartFile>,
        coverIndex: Int
    ): PostResponseDTO {
        val current = getCurrentUser()
        validatePostInput(postRequestDTO)
        require(images.size <= MAX_MEDIA_COUNT) { "At most $MAX_MEDIA_COUNT images are allowed" }
        require(
            coverIndex >= 0 &&
                (images.isEmpty() && coverIndex == 0 || images.isNotEmpty() && coverIndex in images.indices)
        ) { "Cover index is out of range" }
        val userId = if (current.role == Role.ADMIN) postRequestDTO.userId ?: current.userId!! else current.userId!!
        val user = findUserById(userId)
        val uploadedImages = uploadImages(images)
        val savedPost = postRepository.save(createPostEntity(postRequestDTO, user))
        postMediaRepository.saveAll(uploadedImages.mapIndexed { index, image ->
            PostMedia(
                post = savedPost,
                mediaUrl = image.url,
                cloudinaryPublicId = image.publicId,
                displayOrder = index,
                isCover = index == coverIndex
            )
        })

        return mapToResponseDTO(savedPost, current.userId)
    }

    fun createPostAsBot(postRequestDTO: PostRequestDTO): PostResponseDTO {
        val userId = postRequestDTO.userId
            ?: throw IllegalArgumentException("userId is required for bot post creation")
        validatePostInput(postRequestDTO)
        val user = userRepository.findByIdForUpdate(userId)
            ?: throw ResourceNotFoundException()
        if (!user.isEnabled) throw AccessDeniedException("Only verified accounts can post listings")
        val existing = postRequestDTO.idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { postRepository.findByUserUserIdAndIdempotencyKey(userId, it) }
        if (existing != null) return mapToResponseDTO(existing)
        val post = createPostEntity(postRequestDTO, user)
        val savedPost = postRepository.save(post)
        savePostMedia(savedPost, postRequestDTO.imageUrlList, postRequestDTO.imageHashList)
        return mapToResponseDTO(savedPost)
    }

    @Transactional(readOnly = true)
    fun getAllPosts(pageable: Pageable): Page<PostResponseDTO> {
        val currentUserId = getCurrentUserIdOrNull()
        return mapPage(postRepository.findByIsSoldFalseAndStatus(PostStatus.ACTIVE, safePageable(pageable)), currentUserId)
    }

    @Transactional(readOnly = true)
    fun getPostById(postId: Long): PostResponseDTO {
        val post = findPostById(postId)
        val currentUserId = getCurrentUserIdOrNull()
        if (!isVisibleToViewer(post, currentUserId)) throw ResourceNotFoundException()
        return mapToResponseDTO(post, currentUserId)
    }

    @Transactional(readOnly = true)
    fun getPostsByCategory(category: Category, pageable: Pageable): Page<PostResponseDTO> {
        val currentUserId = getCurrentUserIdOrNull()
        return mapPage(postRepository.findByCategoryAndIsSoldFalseAndStatus(category, PostStatus.ACTIVE, safePageable(pageable)), currentUserId)
    }

    @Transactional(readOnly = true)
    fun getPostsByUser(userId: Long, pageable: Pageable): Page<PostResponseDTO> {
        val currentUserId = getCurrentUserIdOrNull()
        val isOwnerOrAdmin = currentUserId == userId || isCurrentUserAdmin()
        val posts = if (isOwnerOrAdmin) {
            postRepository.findByUserUserId(userId, safePageable(pageable))
        } else {
            postRepository.findByUserUserIdAndStatusAndIsSoldFalse(userId, PostStatus.ACTIVE, safePageable(pageable))
        }
        return mapPage(posts, currentUserId)
    }

    @Transactional(readOnly = true)
    fun getAvailablePosts(pageable: Pageable): Page<PostResponseDTO> {
        val currentUserId = getCurrentUserIdOrNull()
        return mapPage(postRepository.findByIsSoldFalseAndStatus(PostStatus.ACTIVE, safePageable(pageable)), currentUserId)
    }

    @Transactional(readOnly = true)
    fun searchAvailablePosts(query: String?, category: Category?, pageable: Pageable): Page<PostResponseDTO> {
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val currentUserId = getCurrentUserIdOrNull()

        val posts = when {
            normalizedQuery == null && category == null -> postRepository.findByIsSoldFalseAndStatus(PostStatus.ACTIVE, safePageable(pageable))
            normalizedQuery == null && category != null -> postRepository.findByCategoryAndIsSoldFalseAndStatus(category, PostStatus.ACTIVE, safePageable(pageable))
            else -> postRepository.searchAvailablePosts(escapeLike(normalizedQuery!!), category, safePageable(pageable))
        }

        return mapPage(posts, currentUserId)
    }

    fun updatePost(postId: Long, postUpdateDTO: PostUpdateDTO): PostResponseDTO {
        val savedPost = saveUpdatedPost(postId, postUpdateDTO)
        if (postUpdateDTO.imageUrlList != null) {
            replacePostMediaWithUrls(savedPost, postUpdateDTO.imageUrlList, postUpdateDTO.imageHashList)
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
        require(!post.isSold && post.status != PostStatus.SOLD) { "Sold listings cannot be deleted" }
        val removedMedia = findOrderedPostMedia(postId)
        favouriteRepository.deleteByPostPostId(postId)
        reviewRepository.deleteBySalePostPostId(postId)
        postRepository.deleteById(postId)
        scheduleRemoteCleanup(removedMedia)
    }

    fun markAsSold(postId: Long, request: MarkSoldRequestDTO): PostResponseDTO {
        val post = postRepository.findByIdForUpdate(postId)
            .orElseThrow { ResourceNotFoundException() }
        assertOwnerOrAdmin(post)
        if (post.isSold) {
            throw ConflictException("Listing is already marked as sold")
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

        val soldAt = LocalDateTime.now(clock)
        if (saleRepository.existsByPostPostId(postId)) {
            throw ConflictException("Listing is already marked as sold")
        }
        val updatedPost = post.copy(isSold = true, buyer = buyer, soldAt = soldAt, status = PostStatus.SOLD)
        val savedPost = postRepository.saveAndFlush(updatedPost)
        saleRepository.save(
            Sale(
                post = savedPost,
                seller = savedPost.user!!,
                buyer = buyer,
                soldAt = soldAt,
                titleSnapshot = savedPost.title,
                priceSnapshot = savedPost.price
            )
        )
        return mapToResponseDTO(savedPost, getCurrentUserIdOrNull())
    }

    fun renewPost(postId: Long, requesterPhone: String? = null): PostResponseDTO {
        val post = postRepository.findByIdForUpdate(postId)
            .orElseThrow { ResourceNotFoundException() }
        if (requesterPhone != null && normalizePhone(requesterPhone) != post.user?.phoneNumber) {
            throw AccessDeniedException("The sender does not own this post")
        }
        if (requesterPhone == null && getCurrentUserIdOrNull() != post.user?.userId && !isCurrentUserAdmin()) {
            throw AccessDeniedException("You do not own this post")
        }
        if (post.isSold || saleRepository.existsByPostPostId(postId)) {
            throw IllegalArgumentException("Sold listings cannot be renewed")
        }
        val now = LocalDateTime.now(clock)
        val activeListingIsExpiring = post.status == PostStatus.ACTIVE &&
            post.expiresAt != null && !post.expiresAt!!.isAfter(now.plus(PostExpirationScheduler.REMINDER_LEAD_TIME))
        if (post.status != PostStatus.ARCHIVED && !activeListingIsExpiring) {
            throw IllegalArgumentException("Only archived or expiring listings can be renewed")
        }
        val renewed = post.copy(
            status = PostStatus.ACTIVE,
            expiresAt = now.plusDays(2),
            reminderSentAt = null
        )
        return mapToResponseDTO(postRepository.save(renewed), getCurrentUserIdOrNull())
    }

    /**
     * Seller contact for the WhatsApp bot. The requester must be a registered,
     * enabled user with a verified phone, matching the web rule that only
     * signed-in members can see seller contact details.
     */
    @Transactional(readOnly = true)
    fun getSellerContactForBot(postId: Long, requesterPhone: String): BotSellerContactDTO {
        val requester = userRepository.findByPhoneNumber(normalizePhone(requesterPhone))
            ?.takeIf { it.isEnabled && it.phoneVerifiedAt != null }
            ?: throw AccessDeniedException("Only registered members can contact sellers")
        val post = findPostById(postId)
        if (post.status != PostStatus.ACTIVE || post.isSold) throw ResourceNotFoundException()
        val seller = post.user ?: throw ResourceNotFoundException()
        if (seller.userId == requester.userId) throw IllegalArgumentException("This is your own listing")
        return BotSellerContactDTO(
            postId = post.postId!!,
            title = post.title,
            sellerName = seller.name,
            whatsappPhone = seller.phoneNumber
        )
    }

    @Transactional(readOnly = true)
    fun resolveMediaUrlsByHash(hashes: Collection<String>): Map<String, String> {
        require(hashes.size <= MAX_MEDIA_RESOLUTION_BATCH) { "Too many image hashes" }
        val validHashes = hashes
            .map(String::trim)
            .filter { it.matches(SHA256_PATTERN) }
            .map(String::lowercase)
            .toSet()
        if (validHashes.isEmpty()) return emptyMap()
        return postMediaRepository.findByImageHashIn(validHashes)
            .associate { it.imageHash!! to it.mediaUrl }
    }

    // Private helper methods for cleaner code

    private fun findUserById(userId: Long): User {
        return userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException() }
    }

    private fun findPostById(postId: Long): Post {
        return postRepository.findById(postId)
            .orElseThrow { ResourceNotFoundException() }
    }

    private fun createPostEntity(postRequestDTO: PostRequestDTO, user: User): Post {
        val now = LocalDateTime.now(clock)
        return Post(
            title = postRequestDTO.title,
            price = normalizePrice(postRequestDTO.price),
            description = postRequestDTO.description,
            category = postRequestDTO.category,
            isSold = false,
            createdAt = now,
            expiresAt = now.plusDays(2),
            user = user,
            idempotencyKey = postRequestDTO.idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun updatePostEntity(existingPost: Post, postUpdateDTO: PostUpdateDTO): Post {
        val title = postUpdateDTO.title?.trim()
        if (title != null && title.isBlank()) throw IllegalArgumentException("Title must not be blank")
        val description = postUpdateDTO.description?.trim()
        if (description != null && description.length < 10) throw IllegalArgumentException("Description must be between 10 and 1000 characters")
        return existingPost.copy(
            title = title ?: existingPost.title,
            price = postUpdateDTO.price?.let(::normalizePrice) ?: existingPost.price,
            description = description ?: existingPost.description,
            category = postUpdateDTO.category ?: existingPost.category
        )
    }

    private fun saveUpdatedPost(postId: Long, postUpdateDTO: PostUpdateDTO): Post {
        val existingPost = findPostById(postId)
        assertOwnerOrAdmin(existingPost)
        require(!existingPost.isSold && existingPost.status != PostStatus.SOLD) { "Sold listings cannot be edited" }
        return postRepository.save(updatePostEntity(existingPost, postUpdateDTO))
    }

    private fun savePostMedia(post: Post, imageUrlList: List<String>?, imageHashList: List<String> = emptyList()) {
        val urls = (imageUrlList ?: emptyList()).map(MediaUrlPolicy::validate)
        require(urls.size <= MAX_MEDIA_COUNT) { "At most $MAX_MEDIA_COUNT images are allowed" }
        require(urls.size == urls.distinct().size) { "Duplicate images are not allowed" }
        val normalizedHashes = normalizeImageHashes(imageHashList, urls)
        postMediaRepository.saveAll(urls.mapIndexed { index, mediaUrl ->
            PostMedia(
                post = post,
                mediaUrl = mediaUrl,
                imageHash = normalizedHashes.getOrNull(index),
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
        require(images.size + existingMediaCount(imageOrder, currentMedia) <= MAX_MEDIA_COUNT) { "At most $MAX_MEDIA_COUNT images are allowed" }
        val uploadedImages = uploadImages(images)
        val uploadedUrls = uploadedImages.map { it.url }
        val orderedUrls = resolveUpdatedMediaUrls(existingUrls, imageOrder, uploadedUrls, coverIndex)

        saveOrderedMediaPreservingIdentity(
            post = post,
            currentMedia = currentMedia,
            orderedUrls = orderedUrls,
            uploadedImages = uploadedImages.associateBy { it.url }
        )
        scheduleUnusedUploadCleanup(uploadedImages, orderedUrls)
    }

    private fun replacePostMediaWithUrls(post: Post, urls: List<String>, hashes: List<String>?) {
        val normalizedUrls = urls.map(MediaUrlPolicy::validate)
        require(normalizedUrls.size <= MAX_MEDIA_COUNT) { "At most $MAX_MEDIA_COUNT images are allowed" }
        require(normalizedUrls.size == normalizedUrls.distinct().size) { "Duplicate images are not allowed" }
        val normalizedHashes = hashes?.let { normalizeImageHashes(it, normalizedUrls) }
        saveOrderedMediaPreservingIdentity(post, findOrderedPostMedia(post.postId!!), normalizedUrls, normalizedHashes)
    }

    private fun saveOrderedMediaPreservingIdentity(
        post: Post,
        currentMedia: List<PostMedia>,
        orderedUrls: List<String>,
        hashes: List<String>? = null,
        uploadedImages: Map<String, CloudinaryService.UploadedImage> = emptyMap()
    ) {
        val byUrl = currentMedia.associateBy { it.mediaUrl }
        // Free the unique order/cover slots before assigning the new order.
        val temporaryStart = maxOf(orderedUrls.size, (currentMedia.maxOfOrNull { it.displayOrder } ?: -1) + 1)
        currentMedia.forEachIndexed { index, media ->
            media.displayOrder = temporaryStart + index
            media.isCover = false
        }
        postMediaRepository.flush()
        val hashUpdates = hashes?.takeIf { it.isNotEmpty() }
        val retained = orderedUrls.mapIndexed { index, mediaUrl ->
            byUrl[mediaUrl]?.also {
                if (hashUpdates != null) it.imageHash = hashUpdates[index]
                it.displayOrder = index
                it.isCover = index == 0
            } ?: PostMedia(
                post = post,
                mediaUrl = mediaUrl,
                imageHash = hashes?.getOrNull(index)?.lowercase(),
                cloudinaryPublicId = uploadedImages[mediaUrl]?.publicId,
                displayOrder = index,
                isCover = index == 0
            )
        }
        val removed = currentMedia.filter { it.mediaUrl !in orderedUrls }
        if (removed.isNotEmpty()) postMediaRepository.deleteAll(removed)
        postMediaRepository.saveAll(retained)
        scheduleRemoteCleanup(removed)
    }

    private fun uploadImages(images: List<org.springframework.web.multipart.MultipartFile>): List<CloudinaryService.UploadedImage> {
        if (images.isEmpty()) return emptyList()

        val uploaded = mutableListOf<CloudinaryService.UploadedImage>()
        try {
            images.forEach { file ->
                uploaded += try {
                    cloudinaryService.uploadImageAsset(file)
                } catch (ex: CloudinaryService.ImageUploadException) {
                    throw DomainValidationException("Failed to upload image. Please try again.")
                }
            }
        } catch (ex: Exception) {
            uploaded.mapNotNull { it.publicId }.forEach(cloudinaryService::deleteImage)
            throw ex
        }

        AfterCommit.onRollback {
            uploaded.mapNotNull { it.publicId }.forEach(cloudinaryService::deleteImage)
        }
        return uploaded
    }

    private fun scheduleUnusedUploadCleanup(
        uploadedImages: List<CloudinaryService.UploadedImage>,
        orderedUrls: List<String>
    ) {
        val unusedIds = uploadedImages
            .filterNot { it.url in orderedUrls }
            .mapNotNull { it.publicId }
        if (unusedIds.isEmpty()) return
        AfterCommit.run { unusedIds.forEach(cloudinaryService::deleteImage) }
    }

    private fun scheduleRemoteCleanup(media: Collection<PostMedia>) {
        val publicIds = media.mapNotNull { it.cloudinaryPublicId }.distinct()
        if (publicIds.isEmpty()) return
        AfterCommit.run {
            publicIds
                .filterNot(postMediaRepository::existsByCloudinaryPublicId)
                .forEach(cloudinaryService::deleteImage)
        }
    }

    private fun existingMediaCount(imageOrder: List<PostImageReferenceDTO>?, currentMedia: List<PostMedia>): Int =
        imageOrder?.count { it.kind.equals("existing", ignoreCase = true) } ?: currentMedia.size

    private fun resolveUpdatedMediaUrls(
        existingUrls: List<String>,
        imageOrder: List<PostImageReferenceDTO>?,
        uploadedUrls: List<String>,
        coverIndex: Int
    ): List<String> {
        val existingSet = existingUrls.toSet()

        val orderedUrls = if (imageOrder == null) {
            existingUrls + uploadedUrls
        } else {
            imageOrder.map { reference ->
                resolveImageReference(reference, existingSet, uploadedUrls)
            }
        }

        require(orderedUrls.size == orderedUrls.distinct().size) { "Duplicate images are not allowed" }
        if (orderedUrls.isEmpty()) {
            require(coverIndex == 0) { "Cover index is out of range" }
            return emptyList()
        }
        require(coverIndex in orderedUrls.indices) { "Cover index is out of range" }

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
        return when (reference.kind.trim().lowercase()) {
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
        return mapToResponseDTO(
            post,
            currentUserId,
            findOrderedPostMedia(post.postId!!),
            currentUserId?.let { favouriteRepository.existsByUserUserIdAndPostPostId(it, post.postId!!) } == true,
            isCurrentUserAdmin()
        )
    }

    internal fun mapPosts(posts: List<Post>, currentUserId: Long?): List<PostResponseDTO> {
        if (posts.isEmpty()) return emptyList()

        val postIds = posts.mapNotNull { it.postId }
        val mediaByPost = postMediaRepository
            .findByPostPostIdInOrderByPostPostIdAscIsCoverDescDisplayOrderAscIdAsc(postIds)
            .groupBy { it.post.postId }
        val favouriteIds = if (currentUserId == null) emptySet() else {
            favouriteRepository.findPostIdsByUserUserIdAndPostPostIdIn(currentUserId, postIds)
        }
        val admin = currentUserId != null && isCurrentUserAdmin()
        return posts.map { post ->
            mapToResponseDTO(
                post,
                currentUserId,
                mediaByPost[post.postId].orEmpty(),
                post.postId!! in favouriteIds,
                admin
            )
        }
    }

    private fun mapToResponseDTO(
        post: Post,
        currentUserId: Long?,
        media: List<PostMedia>,
        isFavourited: Boolean,
        currentUserIsAdmin: Boolean
    ): PostResponseDTO {
        val canViewBuyer = currentUserId != null && (
            currentUserId == post.user?.userId ||
            currentUserId == post.buyer?.userId ||
            currentUserIsAdmin
        )
        // Seller contact details are for signed-in members only; anonymous
        // visitors are prompted to log in before contacting a seller.
        val canViewContact = currentUserId != null
        return PostResponseDTO(
            postId = post.postId!!,
            title = post.title,
            price = post.price,
            mediaUrls = media.map { it.mediaUrl },
            description = post.description,
            category = post.category,
            isSold = post.isSold,
            status = post.status,
            createdAt = post.createdAt,
            expiresAt = post.expiresAt,
            soldAt = post.soldAt,
            user = mapUserToPublicResponseDTO(post.user!!),
            email = if (canViewContact) post.user?.email?.ifBlank { null } else null,
            whatsappPhone = if (canViewContact) post.user?.phoneNumber?.ifBlank { null } else null,
            buyer = if (canViewBuyer) post.buyer?.let { mapUserToPublicResponseDTO(it) } else null,
            isFavourited = isFavourited,
            awaitingBuyerConfirmation = canViewBuyer && post.isSold &&
                saleRepository.findByPostPostId(post.postId!!)?.buyerConfirmedAt == null
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
        return userRepository.findByEmail(IdentityNormalizer.email(email))
            ?: throw ResourceNotFoundException()
    }

    private fun getCurrentUserIdOrNull(): Long? {
        val email = SecurityContextHolder.getContext().authentication?.name ?: return null
        return userRepository.findByEmail(IdentityNormalizer.email(email))?.userId
    }

    private fun isCurrentUserAdmin(): Boolean {
        val email = SecurityContextHolder.getContext().authentication?.name ?: return false
        return userRepository.findByEmail(IdentityNormalizer.email(email))?.role == Role.ADMIN
    }

    private fun assertOwnerOrAdmin(post: Post) {
        val current = getCurrentUser()
        if (current.role != Role.ADMIN && post.user?.userId != current.userId) {
            throw AccessDeniedException("You do not own this post")
        }
    }

    private fun mapPage(page: Page<Post>, currentUserId: Long?): Page<PostResponseDTO> {
        return PageImpl(mapPosts(page.content, currentUserId), page.pageable, page.totalElements)
    }

    private fun safePageable(pageable: Pageable): Pageable {
        val allowed = setOf("createdAt", "postId", "price", "title", "category", "status")
        val requested = pageable.sort.toList()
        if (requested.any { it.property !in allowed }) {
            throw IllegalArgumentException("Unsupported sort field")
        }
        val orders = if (requested.isEmpty()) {
            listOf(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("postId")
            )
        } else if (requested.none { it.property == "postId" }) {
            requested + Sort.Order.desc("postId")
        } else {
            requested
        }
        return PageRequest.of(pageable.pageNumber, pageable.pageSize.coerceIn(1, MAX_PAGE_SIZE), Sort.by(orders))
    }

    internal fun isVisibleToViewer(post: Post, currentUserId: Long?): Boolean {
        if (post.status == PostStatus.ACTIVE && !post.isSold) return true
        if (currentUserId == null) return false
        if (currentUserId == post.user?.userId || currentUserId == post.buyer?.userId) return true
        return isCurrentUserAdmin()
    }

    private fun validatePostInput(request: PostRequestDTO) {
        require(request.title.trim().isNotEmpty()) { "Title is required" }
        require(request.description.trim().length in 10..1000) { "Description must be between 10 and 1000 characters" }
        normalizePrice(request.price)
        val urls = request.imageUrlList.orEmpty()
        require(urls.size <= MAX_MEDIA_COUNT) { "At most $MAX_MEDIA_COUNT images are allowed" }
        urls.forEach(MediaUrlPolicy::validate)
        normalizeImageHashes(request.imageHashList, urls)
    }

    private fun normalizeImageHashes(hashes: List<String>, urls: List<String>): List<String> {
        require(hashes.isEmpty() || hashes.size == urls.size) {
            "Image hashes must match the image list"
        }
        hashes.forEach { hash ->
            require(hash.matches(SHA256_PATTERN)) { "Image hash must be SHA-256" }
        }
        return hashes.map(String::lowercase)
    }

    private fun normalizePrice(price: java.math.BigDecimal): java.math.BigDecimal {
        require(price.scale() <= 2) { "Price must have at most two decimal places" }
        return price.setScale(2, RoundingMode.UNNECESSARY)
    }

    private fun normalizePhone(phone: String): String = IdentityNormalizer.phone(phone)

    companion object {
        private const val MAX_MEDIA_COUNT = 5
        private const val MAX_PAGE_SIZE = 50
        private const val MAX_MEDIA_RESOLUTION_BATCH = 50
        private val SHA256_PATTERN = Regex("[A-Fa-f0-9]{64}")
    }
}
