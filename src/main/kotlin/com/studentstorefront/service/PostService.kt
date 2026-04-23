package com.studentstorefront.service

import com.studentstorefront.dto.request.PostRequestDTO
import com.studentstorefront.dto.response.PostResponseDTO
import com.studentstorefront.dto.response.SellerResponseDTO
import com.studentstorefront.dto.update.PostUpdateDTO
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.Role
import com.studentstorefront.entity.Post
import com.studentstorefront.entity.Seller
import com.studentstorefront.entity.PostMedia
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PostMediaRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.SellerRepository
import org.hibernate.internal.util.collections.ArrayHelper.forEach
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
    private val cloudinaryService: CloudinaryService
) {

    fun createPost(postRequestDTO: PostRequestDTO): PostResponseDTO {
        val current = getCurrentSeller()
        val sellerId = if (current.role == Role.ADMIN) postRequestDTO.sellerId!! else current.sellerId!!
        val seller = findSellerById(sellerId)
        val post = createPostEntity(postRequestDTO, seller)
        val savedPost = postRepository.save(post)
        postMediaRepository.saveAll((postRequestDTO.imageUrlList ?: emptyList()).map { PostMedia(post = savedPost, mediaUrl = it) })
        return mapToResponseDTO(savedPost)
    }

    fun createPostWithImages(
        postRequestDTO: PostRequestDTO,
        images: List<org.springframework.web.multipart.MultipartFile>,
        coverIndex: Int
    ): PostResponseDTO {
        val current = getCurrentSeller()
        val sellerId = if (current.role == Role.ADMIN) postRequestDTO.sellerId!! else current.sellerId!!
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

        return mapToResponseDTO(savedPost)
    }

    fun createPostAsBot(postRequestDTO: PostRequestDTO): PostResponseDTO {
        val sellerId = postRequestDTO.sellerId
            ?: throw IllegalArgumentException("sellerId is required for bot post creation")
        val seller = findSellerById(sellerId)
        val post = createPostEntity(postRequestDTO, seller)
        val savedPost = postRepository.save(post)
        postMediaRepository.saveAll((postRequestDTO.imageUrlList ?: emptyList()).map { PostMedia(post = savedPost, mediaUrl = it) })
        return mapToResponseDTO(savedPost)
    }

    @Transactional(readOnly = true)
    fun getAllPosts(pageable: Pageable): Page<PostResponseDTO> {
        return postRepository.findAll(pageable).map { mapToResponseDTO(it) }
    }

    @Transactional(readOnly = true)
    fun getPostById(postId: Long): PostResponseDTO {
        val post = findPostById(postId)
        return mapToResponseDTO(post)
    }

    @Transactional(readOnly = true)
    fun getPostsByCategory(category: Category, pageable: Pageable): Page<PostResponseDTO> {
        return postRepository.findByCategory(category, pageable).map { mapToResponseDTO(it) }
    }

    @Transactional(readOnly = true)
    fun getPostsBySeller(sellerId: Long, pageable: Pageable): Page<PostResponseDTO> {
        return postRepository.findBySellerSellerId(sellerId, pageable).map { mapToResponseDTO(it) }
    }

    @Transactional(readOnly = true)
    fun getAvailablePosts(pageable: Pageable): Page<PostResponseDTO> {
        return postRepository.findByIsSoldFalse(pageable).map { mapToResponseDTO(it) }
    }

    fun updatePost(postId: Long, postUpdateDTO: PostUpdateDTO): PostResponseDTO {
        val existingPost = findPostById(postId)
        assertOwnerOrAdmin(existingPost)
        val updatedPost = updatePostEntity(existingPost, postUpdateDTO)
        val savedPost = postRepository.save(updatedPost)
        if (postUpdateDTO.imageUrlList != null) {
            postMediaRepository.deleteAll(postMediaRepository.findByPost_postId(postId))
            postMediaRepository.saveAll(postUpdateDTO.imageUrlList.map { PostMedia(post = savedPost, mediaUrl = it) })

        }
        return mapToResponseDTO(savedPost)
    }

    fun deletePost(postId: Long) {
        val post = findPostById(postId)
        assertOwnerOrAdmin(post)
        favouriteRepository.deleteByPostPostId(postId)
        postRepository.deleteById(postId)
    }

    fun markAsSold(postId: Long): PostResponseDTO {
        val post = findPostById(postId)
        assertOwnerOrAdmin(post)
        val updatedPost = post.copy(isSold = true)
        val savedPost = postRepository.save(updatedPost)
        return mapToResponseDTO(savedPost)
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
            expiresAt = postRequestDTO.expiresAt,
            seller = seller
        )
    }

    private fun updatePostEntity(existingPost: Post, postUpdateDTO: PostUpdateDTO): Post {
        return existingPost.copy(
            title = postUpdateDTO.title ?: existingPost.title,
            price = postUpdateDTO.price ?: existingPost.price,
            description = postUpdateDTO.description ?: existingPost.description,
            category = postUpdateDTO.category ?: existingPost.category,
            isSold = postUpdateDTO.isSold ?: existingPost.isSold,
            expiresAt = postUpdateDTO.expiresAt ?: existingPost.expiresAt
        )
    }

    private fun mapToResponseDTO(post: Post): PostResponseDTO {
        return PostResponseDTO(
            postId = post.postId!!,
            title = post.title,
            price = post.price,
            mediaUrls = postMediaRepository.findByPost_postId(post.postId!!)
                .sortedWith(compareBy({ !it.isCover }, { it.displayOrder }))
                .map { it.mediaUrl },
            description = post.description,
            category = post.category,
            isSold = post.isSold,
            createdAt = post.createdAt,
            expiresAt = post.expiresAt,
            seller = mapSellerToResponseDTO(post.seller!!)
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

    private fun getCurrentSeller(): Seller {
        val email = SecurityContextHolder.getContext().authentication?.name
        return sellerRepository.findByEmail(email.toString())
            ?: throw IllegalArgumentException("Authenticated seller not found")
    }

    private fun assertOwnerOrAdmin(post: Post) {
        val current = getCurrentSeller()
        if (current.role != Role.ADMIN && post.seller?.sellerId != current.sellerId) {
            throw AccessDeniedException("You do not own this post")
        }
    }
}