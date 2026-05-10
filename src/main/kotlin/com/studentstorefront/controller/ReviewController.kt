package com.studentstorefront.controller

import com.studentstorefront.dto.request.ReviewRequestDTO
import com.studentstorefront.dto.response.ProfileReviewsResponseDTO
import com.studentstorefront.dto.response.ReviewContextResponseDTO
import com.studentstorefront.dto.response.ReviewResponseDTO
import com.studentstorefront.service.ReviewService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/reviews")
@CrossOrigin(originPatterns = ["http://localhost:*", "http://127.0.0.1:*", "http://free2:*", "https://*.duckdns.org"])
class ReviewController(
    private val reviewService: ReviewService
) {

    @PostMapping
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun createReview(@Valid @RequestBody request: ReviewRequestDTO): ResponseEntity<ReviewResponseDTO> {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.createReview(request))
    }

    @GetMapping("/context/{postId}")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun getReviewContext(@PathVariable postId: Long): ResponseEntity<ReviewContextResponseDTO> {
        return ResponseEntity.ok(reviewService.getReviewContext(postId))
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun getPendingReviewContexts(): ResponseEntity<List<ReviewContextResponseDTO>> {
        return ResponseEntity.ok(reviewService.getPendingReviewContexts())
    }

    @GetMapping("/profile/{sellerId}")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun getProfileReviews(@PathVariable sellerId: Long): ResponseEntity<ProfileReviewsResponseDTO> {
        return ResponseEntity.ok(reviewService.getProfileReviews(sellerId))
    }
}
