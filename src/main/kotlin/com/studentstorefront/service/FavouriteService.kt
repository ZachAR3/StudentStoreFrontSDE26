package com.studentstorefront.service

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
    private val sellerRepository: SellerRepository
) {

    @Transactional(readOnly = true)
    fun getFavouritePostIds(): List<Long> {
        val seller = getCurrentSeller()
        return favouriteRepository.findBySellerSellerId(seller.sellerId!!)
            .map { it.post.postId!! }
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

    // Helper
    private fun getCurrentSeller(): Seller {
        val email = SecurityContextHolder.getContext().authentication?.name
        return sellerRepository.findByEmail(email.toString())
            ?: throw IllegalArgumentException("Authenticated seller not found")
    }
}
