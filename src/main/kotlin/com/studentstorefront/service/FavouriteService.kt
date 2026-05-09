package com.studentstorefront.service

import com.studentstorefront.dto.response.PostResponseDTO
import com.studentstorefront.entity.Favourite
import com.studentstorefront.entity.Seller
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.SellerRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class FavouriteService(
    private val favouriteRepository: FavouriteRepository,
    private val postRepository: PostRepository,
    private val sellerRepository: SellerRepository,
    private val postService: PostService
) {

    @Transactional(readOnly = true)
    fun getFavouritePosts(): List<PostResponseDTO> {
        val seller = getCurrentSeller()
        return favouriteRepository.findBySellerSellerId(seller.sellerId!!)
            .map { postService.mapToResponseDTO(it.post, seller.sellerId) }
    }

    fun addFavourite(postId: Long) {
        val seller = getCurrentSeller()
        
        // Check if post exists
        val post = postRepository.findById(postId)
            .orElseThrow { IllegalArgumentException("Post not found with id: $postId") }
            
        // Check if already favourited
        if (favouriteRepository.existsBySellerSellerIdAndPostPostId(seller.sellerId!!, postId)) {
            return // Idempotent
        }
        
        val favourite = Favourite(seller = seller, post = post)
        favouriteRepository.save(favourite)
    }

    fun removeFavourite(postId: Long) {
        val seller = getCurrentSeller()
        favouriteRepository.deleteBySellerSellerIdAndPostPostId(seller.sellerId!!, postId)
    }

    private fun getCurrentSeller(): Seller {
        val email = SecurityContextHolder.getContext().authentication?.name
            ?: throw IllegalArgumentException("Not authenticated")
        return sellerRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Authenticated seller not found")
    }
}
