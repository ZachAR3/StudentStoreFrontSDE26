package com.studentstorefront.controller

import com.studentstorefront.dto.request.PostRequestDTO
import com.studentstorefront.dto.response.PostResponseDTO
import com.studentstorefront.dto.update.PostUpdateDTO
import com.studentstorefront.enums.Category
import com.studentstorefront.service.PostService
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/posts")
@CrossOrigin(origins = ["http://localhost:8080", "http://127.0.0.1:8080", "http://localhost:3000"])
class PostController(private val postService: PostService) {

    @PostMapping
    fun createPost(@Valid @RequestBody postRequestDTO: PostRequestDTO): ResponseEntity<PostResponseDTO> {
        val createdPost = postService.createPost(postRequestDTO)
        return ResponseEntity.status(HttpStatus.CREATED).body(createdPost)
    }

    @GetMapping
    fun getAllPosts(
        @PageableDefault(size = 20) pageable: Pageable
    ): ResponseEntity<Page<PostResponseDTO>> {
        val posts = postService.getAllPosts(pageable)
        return ResponseEntity.ok(posts)
    }

    @GetMapping("/{postId}")
    fun getPostById(@PathVariable postId: Long): ResponseEntity<PostResponseDTO> {
        val post = postService.getPostById(postId)
        return ResponseEntity.ok(post)
    }

    @GetMapping("/category/{category}")
    fun getPostsByCategory(
        @PathVariable category: String,
        @PageableDefault(size = 20) pageable: Pageable
    ): ResponseEntity<Page<PostResponseDTO>> {
        val categoryEnum = runCatching {
            Category.valueOf(category.uppercase())
        }.getOrNull()

        return if (categoryEnum != null) {
            val posts = postService.getPostsByCategory(categoryEnum, pageable)
            ResponseEntity.ok(posts)
        } else {
            ResponseEntity.badRequest().build()
        }
    }

    @GetMapping("/seller/{sellerId}")
    fun getPostsBySeller(
        @PathVariable sellerId: Long,
        @PageableDefault(size = 20) pageable: Pageable
    ): ResponseEntity<Page<PostResponseDTO>> {
        val posts = postService.getPostsBySeller(sellerId, pageable)
        return ResponseEntity.ok(posts)
    }

    @GetMapping("/available")
    fun getAvailablePosts(
        @PageableDefault(size = 20) pageable: Pageable
    ): ResponseEntity<Page<PostResponseDTO>> {
        val posts = postService.getAvailablePosts(pageable)
        return ResponseEntity.ok(posts)
    }

    @PutMapping("/{postId}")
    fun updatePost(
        @PathVariable postId: Long,
        @Valid @RequestBody postUpdateDTO: PostUpdateDTO
    ): ResponseEntity<PostResponseDTO> {
        val updatedPost = postService.updatePost(postId, postUpdateDTO)
        return ResponseEntity.ok(updatedPost)
    }

    @PatchMapping("/{postId}/mark-sold")
    fun markAsSold(@PathVariable postId: Long): ResponseEntity<PostResponseDTO> {
        val updatedPost = postService.markAsSold(postId)
        return ResponseEntity.ok(updatedPost)
    }

    @DeleteMapping("/{postId}")
    fun deletePost(@PathVariable postId: Long): ResponseEntity<Void> {
        postService.deletePost(postId)
        return ResponseEntity.noContent().build()
    }
}
