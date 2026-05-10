package com.studentstorefront.controller

import com.studentstorefront.dto.request.PostRequestDTO
import com.studentstorefront.dto.request.MarkSoldRequestDTO
import com.studentstorefront.dto.response.PostResponseDTO
import com.studentstorefront.dto.update.PostUpdateDTO
import com.studentstorefront.enums.Category
import com.studentstorefront.service.PostService
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/posts")
@CrossOrigin(originPatterns = ["http://localhost:*", "http://127.0.0.1:*", "http://free2:*", "https://*.duckdns.org"])
class PostController(
    private val postService: PostService,
    @Value("\${whatsapp.bot.api-key}") private val botApiKey: String
) {

    @PostMapping
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun createPost(@Valid @RequestBody postRequestDTO: PostRequestDTO): ResponseEntity<PostResponseDTO> {
        val createdPost = postService.createPost(postRequestDTO)
        return ResponseEntity.status(HttpStatus.CREATED).body(createdPost)
    }

    @PostMapping("/upload", consumes = ["multipart/form-data"])
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun createPostWithImages(
        @RequestPart("post") @Valid postRequestDTO: PostRequestDTO,
        @RequestPart("images") images: List<org.springframework.web.multipart.MultipartFile>,
        @RequestParam(value = "coverIndex", defaultValue = "0") coverIndex: Int
    ): ResponseEntity<PostResponseDTO> {
        val createdPost = postService.createPostWithImages(postRequestDTO, images, coverIndex)
        return ResponseEntity.status(HttpStatus.CREATED).body(createdPost)
    }

    @PostMapping("/bot")
    fun createPostAsBot(
        @RequestHeader("X-Bot-Api-Key") apiKey: String,
        @Valid @RequestBody postRequestDTO: PostRequestDTO
    ): ResponseEntity<PostResponseDTO> {
        if (apiKey != botApiKey) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val createdPost = postService.createPostAsBot(postRequestDTO)
        return ResponseEntity.status(HttpStatus.CREATED).body(createdPost)
    }

    @PostMapping("/bot/media/resolve")
    fun resolveBotMediaUrls(
        @RequestHeader("X-Bot-Api-Key") apiKey: String,
        @RequestBody imageHashes: List<String>
    ): ResponseEntity<Map<String, String>> {
        if (apiKey != botApiKey) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.ok(postService.resolveMediaUrlsByHash(imageHashes))
    }

    @PostMapping("/bot/renew/{postId}")
    fun renewPost(
        @RequestHeader("X-Bot-Api-Key") apiKey: String,
        @PathVariable postId: Long
    ): ResponseEntity<PostResponseDTO> {
        if (apiKey != botApiKey) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val renewed = postService.renewPost(postId)
        return ResponseEntity.ok(renewed)
    }

    @GetMapping
    fun getAllPosts(
        @PageableDefault(size = 20) pageable: Pageable
    ): ResponseEntity<Page<PostResponseDTO>> {
        val posts = postService.getAllPosts(pageable)
        return ResponseEntity.ok(posts)
    }

    @GetMapping("/search")
    fun searchPosts(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) category: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable
    ): ResponseEntity<Page<PostResponseDTO>> {
        val categoryEnum = category?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { Category.valueOf(it.uppercase()) }.getOrNull()
                ?: return ResponseEntity.badRequest().build()
        }

        val posts = postService.searchAvailablePosts(q, categoryEnum, pageable)
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
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun updatePost(
        @PathVariable postId: Long,
        @Valid @RequestBody postUpdateDTO: PostUpdateDTO
    ): ResponseEntity<PostResponseDTO> {
        val updatedPost = postService.updatePost(postId, postUpdateDTO)
        return ResponseEntity.ok(updatedPost)
    }

    @PatchMapping("/{postId}/mark-sold")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun markAsSold(
        @PathVariable postId: Long,
        @Valid @RequestBody request: MarkSoldRequestDTO
    ): ResponseEntity<PostResponseDTO> {
        val updatedPost = postService.markAsSold(postId, request)
        return ResponseEntity.ok(updatedPost)
    }

    @DeleteMapping("/{postId}")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun deletePost(@PathVariable postId: Long): ResponseEntity<Void> {
        postService.deletePost(postId)
        return ResponseEntity.noContent().build()
    }
}
