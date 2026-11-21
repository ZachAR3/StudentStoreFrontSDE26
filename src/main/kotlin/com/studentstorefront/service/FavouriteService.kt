package com.studentstorefront.service

import com.studentstorefront.dto.response.PostResponseDTO
import com.studentstorefront.entity.Favourite
import com.studentstorefront.entity.User
import com.studentstorefront.exception.ResourceNotFoundException
import com.studentstorefront.repository.FavouriteRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
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
        val favourites = favouriteRepository.findByUserUserId(
            user.userId!!,
            PageRequest.of(
                0,
                MAX_FAVOURITES,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
            )
        )
        return postService.mapPosts(
            favourites.map { it.post }.filter { postService.isVisibleToViewer(it, user.userId) },
            user.userId
        )
    }

    fun addFavourite(postId: Long) {
        val user = getCurrentUser()
        
        // Check if post exists
        val post = postRepository.findById(postId)
            .orElseThrow { ResourceNotFoundException() }
        if (!postService.isVisibleToViewer(post, user.userId)) throw ResourceNotFoundException()
            
        // The unique key and conflict-safe insert make concurrent requests idempotent.
        favouriteRepository.insertIfAbsent(user.userId!!, post.postId!!)
    }

    fun removeFavourite(postId: Long) {
        val user = getCurrentUser()
        favouriteRepository.deleteByUserUserIdAndPostPostId(user.userId!!, postId)
    }

    private fun getCurrentUser(): User {
        val email = SecurityContextHolder.getContext().authentication?.name
            ?: throw IllegalArgumentException("Not authenticated")
        return userRepository.findByEmail(IdentityNormalizer.email(email))
            ?: throw IllegalArgumentException("Authenticated user not found")
    }

    companion object {
        private const val MAX_FAVOURITES = 100
    }
}
