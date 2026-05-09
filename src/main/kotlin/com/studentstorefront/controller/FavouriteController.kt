package com.studentstorefront.controller

import com.studentstorefront.dto.response.PostResponseDTO
import com.studentstorefront.service.FavouriteService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/favourites")
@CrossOrigin(origins = ["http://localhost:8080", "http://127.0.0.1:8080", "http://localhost:3000"])
class FavouriteController(
    private val favouriteService: FavouriteService
) {

    @GetMapping
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun getFavouritePosts(): ResponseEntity<List<PostResponseDTO>> {
        return ResponseEntity.ok(favouriteService.getFavouritePosts())
    }

    @PostMapping("/{postId}")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun addFavourite(@PathVariable postId: Long): ResponseEntity<Void> {
        favouriteService.addFavourite(postId)
        return ResponseEntity.ok().build()
    }

    @DeleteMapping("/{postId}")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    fun removeFavourite(@PathVariable postId: Long): ResponseEntity<Void> {
        favouriteService.removeFavourite(postId)
        return ResponseEntity.noContent().build()
    }
}
