package com.studentstorefront.service

import com.studentstorefront.dto.request.PostRequestDTO
import com.studentstorefront.dto.response.PostResponseDTO
import com.studentstorefront.dto.response.SellerResponseDTO
import com.studentstorefront.dto.update.PostUpdateDTO
import com.studentstorefront.enums.Category
import com.studentstorefront.entity.Post
import com.studentstorefront.entity.Seller
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.SellerRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime


@Service
@Transactional
class PostService(
    private val postRepository: PostRepository,
    private val sellerRepository: SellerRepository
) {

    fun createPost(postRequestDTO: PostRequestDTO): PostResponseDTO {
        val seller = findSellerById(postRequestDTO.sellerId!!)
        val post = createPostEntity(postRequestDTO, seller)
        val savedPost = postRepository.save(post)
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
        val updatedPost = updatePostEntity(existingPost, postUpdateDTO)
        val savedPost = postRepository.save(updatedPost)
        return mapToResponseDTO(savedPost)
    }

    fun deletePost(postId: Long) {
        if (!postRepository.existsById(postId)) {
            throw IllegalArgumentException("Post not found with id: $postId")
        }
        postRepository.deleteById(postId)
    }

    fun markAsSold(postId: Long): PostResponseDTO {
        val post = findPostById(postId)
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
            imageUrl = postRequestDTO.imageUrl,
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
            imageUrl = postUpdateDTO.imageUrl ?: existingPost.imageUrl,
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
            imageUrl = post.imageUrl,
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
}