package com.studentstorefront.service

import com.studentstorefront.dto.request.PostRequestDTO
import com.studentstorefront.dto.request.MarkSoldRequestDTO
import com.studentstorefront.dto.response.PostResponseDTO
import com.studentstorefront.dto.response.SellerResponseDTO
import com.studentstorefront.dto.update.PostImageReferenceDTO
import com.studentstorefront.dto.update.PostUpdateDTO
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import com.studentstorefront.enums.Role
import com.studentstorefront.entity.Post
import com.studentstorefront.entity.Seller
import com.studentstorefront.entity.PostMedia
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PostMediaRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.ReviewRepository
import com.studentstorefront.repository.SellerRepository
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
    private val sellerRepository: SellerRepository,
    private val postMediaRepository: PostMediaRepository,
    private val favouriteRepository: FavouriteRepository,
    private val reviewRepository: ReviewRepository,
    private val cloudinaryService: CloudinaryService,
    private val botNotificationService: BotNotificationService
) {

    fun createPost(postRequestDTO: PostRequestDTO): PostResponseDTO {
        val current = getCurrentSeller()
        val sellerId = if (current.role == Role.ADMIN) postRequestDTO.sellerId ?: current.sellerId!! else current.sellerId!!
        val seller = findSellerById(sellerId)
        val post = createPostEntity(postRequestDTO, seller)
        val savedPost = postRepository.save(post)
        savePostMedia(savedPost, postRequestDTO.imageUrlList, postRequestDTO.imageHashList)
        return mapToResponseDTO(savedPost, current.sellerId)
    }

    fun createPostWithImages(
        postRequestDTO: PostRequestDTO,
        images: List<org.springframework.web.multipart.MultipartFile>,
        coverIndex: Int
    ): PostResponseDTO {
        val current = getCurrentSeller()
        val sellerId = if (current.role == Role.ADMIN) postRequestDTO.sellerId ?: current.sellerId!! else current.sellerId!!
        val seller = findSellerById(sellerId)
        val post = createPostEntity(postRequestDTO, seller)
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

        return mapToResponseDTO(savedPost, current.sellerId)
    }

    fun createPostAsBot(postRequestDTO: PostRequestDTO): PostResponseDTO {
        val sellerId = postRequestDTO.sellerId
            ?: throw IllegalArgumentException("sellerId is required for bot post creation")
        val seller = findSellerById(sellerId)
        val post = createPostEntity(postRequestDTO, seller)
        val savedPost = postRepository.save(post)
        savePostMedia(savedPost, postRequestDTO.imageUrlList, postRequestDTO.imageHashList)
        return mapToResponseDTO(savedPost)
    }

    @Transactional(readOnly = true)
    fun getAllPosts(pageable: Pageable): Page<PostResponseDTO> {
        val currentSellerId = getCurrentSellerIdOrNull()
        return postRepository.findAll(pageable).map { mapToResponseDTO(it, currentSellerId) }
    }

    @Transactional(readOnly = true)
    fun getPostById(postId: Long): PostResponseDTO {
        val post = findPostById(postId)
        return mapToResponseDTO(post, getCurrentSellerIdOrNull())
    }

    @Transactional(readOnly = true)
    fun getPostsByCategory(category: Category, pageable: Pageable): Page<PostResponseDTO> {
        val currentSellerId = getCurrentSellerIdOrNull()
        return postRepository.findByCategoryAndStatus(category, PostStatus.ACTIVE, pageable).map { mapToResponseDTO(it, currentSellerId) }
    }

    @Transactional(readOnly = true)
    fun getPostsBySeller(sellerId: Long, pageable: Pageable): Page<PostResponseDTO> {
        val currentSellerId = getCurrentSellerIdOrNull()
        return postRepository.findBySellerSellerId(sellerId, pageable).map { mapToResponseDTO(it, currentSellerId) }
    }

    @Transactional(readOnly = true)
    fun getAvailablePosts(pageable: Pageable): Page<PostResponseDTO> {
        val currentSellerId = getCurrentSellerIdOrNull()
        return postRepository.findByIsSoldFalseAndStatus(PostStatus.ACTIVE, pageable).map { mapToResponseDTO(it, currentSellerId) }
    }

    @Transactional(readOnly = true)
    fun searchAvailablePosts(query: String?, category: Category?, pageable: Pageable): Page<PostResponseDTO> {
        val normalizedQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        val currentSellerId = getCurrentSellerIdOrNull()

        val posts = when {
            normalizedQuery == null && category == null -> postRepository.findByIsSoldFalseAndStatus(PostStatus.ACTIVE, pageable)
            normalizedQuery == null && category != null -> postRepository.findByCategoryAndIsSoldFalseAndStatus(category, PostStatus.ACTIVE, pageable)
            else -> postRepository.searchAvailablePosts(escapeLike(normalizedQuery!!), category, pageable)
        }

        return posts.map { mapToResponseDTO(it, currentSellerId) }
    }

    fun updatePost(postId: Long, postUpdateDTO: PostUpdateDTO): PostResponseDTO {
        val savedPost = saveUpdatedPost(postId, postUpdateDTO)
        if (postUpdateDTO.imageUrlList != null) {
            postMediaRepository.deleteAll(postMediaRepository.findByPost_postId(postId))
            savePostMedia(savedPost, postUpdateDTO.imageUrlList, null)

        }
        return mapToResponseDTO(savedPost, getCurrentSellerIdOrNull())
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

        return mapToResponseDTO(savedPost, getCurrentSellerIdOrNull())
    }

    fun deletePost(postId: Long) {
        val post = findPostById(postId)
        assertOwnerOrAdmin(post)
        favouriteRepository.deleteByPostPostId(postId)
        reviewRepository.deleteByPostPostId(postId)
        postRepository.deleteById(postId)
    }

    fun markAsSold(postId: Long, request: MarkSoldRequestDTO): PostResponseDTO {
        val post = findPostById(postId)
        assertOwnerOrAdmin(post)
        if (post.isSold) {
            throw IllegalArgumentException("Listing is already marked as sold")
        }
        val buyerId = request.buyerId ?: throw IllegalArgumentException("Buyer ID is required")
        val buyer = findSellerById(buyerId)
        if (buyer.sellerId == post.seller?.sellerId) {
            throw IllegalArgumentException("Seller cannot select themselves as the buyer")
        }

        val updatedPost = post.copy(isSold = true, buyer = buyer, soldAt = LocalDateTime.now())
        val savedPost = postRepository.save(updatedPost)
        botNotificationService.sendSellerReviewRequest(savedPost)
        return mapToResponseDTO(savedPost, getCurrentSellerIdOrNull())
    }

    fun renewPost(postId: Long): PostResponseDTO {
        val post = findPostById(postId)
        val renewed = post.copy(
            status = PostStatus.ACTIVE,
            expiresAt = LocalDateTime.now().plusDays(2),
            reminderSentAt = null
        )
        return mapToResponseDTO(postRepository.save(renewed), getCurrentSellerIdOrNull())
    }

    // Private helper methods for cleaner code

    private fun findSellerById(sellerId: Long): Seller {
        return sellerRepository.findById(sellerId)
            .orElseThrow { IllegalArgumentException("Seller not found with id: $sellerId") }
    }

    private fun findPostById(postId: Long): Post {
        return postRepository.findById(postId)
            .orElseThrow { IllegalArgumentException("Post not found with id: $postId") }
    }

    private fun createPostEntity(postRequestDTO: PostRequestDTO, seller: Seller): Post {
        return Post(
            title = postRequestDTO.title,
            price = postRequestDTO.price,
            description = postRequestDTO.description,
            category = postRequestDTO.category,
            isSold = postRequestDTO.isSold ?: false,
            createdAt = LocalDateTime.now(),
            expiresAt = LocalDateTime.now().plusDays(2),
            seller = seller
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
    fun resolveMediaUrlsByHash(imageHashes: List<String>): Map<String, String> {
        if (imageHashes.isEmpty()) return emptyMap()

        return postMediaRepository.findByImageHashIn(imageHashes.distinct())
            .filter { !it.imageHash.isNullOrBlank() }
            .distinctBy { it.imageHash }
            .associate { it.imageHash!! to it.mediaUrl }
    }

    private fun savePostMedia(post: Post, imageUrlList: List<String>?, imageHashList: List<String>?) {
        postMediaRepository.saveAll((imageUrlList ?: emptyList()).mapIndexed { index, mediaUrl ->
            val imageHash = imageHashList?.getOrNull(index)?.takeIf { it.isNotBlank() }
            val existingUrl = imageHash?.let { resolveMediaUrlsByHash(listOf(it))[it] }

            PostMedia(
                post = post,
                mediaUrl = existingUrl ?: mediaUrl,
                imageHash = imageHash,
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

    internal fun mapToResponseDTO(post: Post, currentSellerId: Long? = null): PostResponseDTO {
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
            seller = mapSellerToResponseDTO(post.seller!!),
            buyer = post.buyer?.let { mapSellerToResponseDTO(it) },
            isFavourited = currentSellerId != null &&
                favouriteRepository.existsBySellerSellerIdAndPostPostId(currentSellerId, post.postId!!)
        )
    }

    private fun mapSellerToResponseDTO(seller: Seller): SellerResponseDTO {
        return SellerResponseDTO(
            sellerId = seller.sellerId!!,
            name = seller.name,
            email = seller.email,
            phoneNumber = seller.phoneNumber
        )
    }

    private fun findOrderedPostMedia(postId: Long): List<PostMedia> =
        postMediaRepository.findByPost_postId(postId)
            .sortedWith(compareBy({ !it.isCover }, { it.displayOrder }))

    private fun escapeLike(input: String): String =
        input.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private fun getCurrentSeller(): Seller {
        val email = SecurityContextHolder.getContext().authentication?.name
            ?: throw IllegalArgumentException("Not authenticated")
        return sellerRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Authenticated seller not found")
    }

    private fun getCurrentSellerIdOrNull(): Long? {
        val email = SecurityContextHolder.getContext().authentication?.name ?: return null
        return sellerRepository.findByEmail(email)?.sellerId
    }

    private fun assertOwnerOrAdmin(post: Post) {
        val current = getCurrentSeller()
        if (current.role != Role.ADMIN && post.seller?.sellerId != current.sellerId) {
            throw AccessDeniedException("You do not own this post")
        }
    }
}
