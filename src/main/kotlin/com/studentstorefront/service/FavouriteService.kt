package com.studentstorefront.service

import com.studentstorefront.dto.response.PostResponseDTO
import com.studentstorefront.entity.Favourite
import com.studentstorefront.entity.User
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.UserRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class FavouriteService(
    private val favouriteRepository: FavouriteRepository,
    private val postRepository: PostRepository,
    private val userRepository: UserRepository,
    private val postService: PostService
) {

    @Transactional(readOnly = true)
    fun getFavouritePosts(): List<PostResponseDTO> {
        val user = getCurrentUser()
        return favouriteRepository.findByUserUserId(user.userId!!)
            .map { postService.mapToResponseDTO(it.post, user.userId) }
    }

    fun addFavourite(postId: Long) {
        val user = getCurrentUser()
        
        // Check if post exists
        val post = postRepository.findById(postId)
            .orElseThrow { IllegalArgumentException("Post not found with id: $postId") }
            
        // Check if already favourited
        if (favouriteRepository.existsByUserUserIdAndPostPostId(user.userId!!, postId)) {
            return // Idempotent
        }
        
        val favourite = Favourite(user = user, post = post)
        favouriteRepository.save(favourite)
    }

    fun removeFavourite(postId: Long) {
        val user = getCurrentUser()
        favouriteRepository.deleteByUserUserIdAndPostPostId(user.userId!!, postId)
    }

    private fun getCurrentUser(): User {
        val email = SecurityContextHolder.getContext().authentication?.name
            ?: throw IllegalArgumentException("Not authenticated")
        return userRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Authenticated user not found")
    }
}
