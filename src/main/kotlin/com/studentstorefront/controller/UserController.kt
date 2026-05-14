package com.studentstorefront.controller

import com.studentstorefront.dto.request.DeleteAccountRequestDTO
import com.studentstorefront.dto.request.UserRequestDTO
import com.studentstorefront.dto.response.PublicUserResponseDTO
import com.studentstorefront.dto.response.UserResponseDTO
import com.studentstorefront.dto.update.UserUpdateDTO
import com.studentstorefront.service.UserService
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
@CrossOrigin(originPatterns = ["http://localhost:*", "http://127.0.0.1:*", "http://free2:*", "https://*.duckdns.org"])
class UserController(
    private val userService: UserService,
    @Value("\${whatsapp.bot.api-key}") private val botApiKey: String
) {

    /**
     * Self-service account deletion. Requires password re-entry for safety.
     * Mapped before /{userId} to prevent Spring from treating "me" as a path variable.
     */
    @DeleteMapping("/me")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    fun deleteOwnAccount(@Valid @RequestBody request: DeleteAccountRequestDTO): ResponseEntity<Void> {
        userService.deleteOwnAccount(request.password)
        return ResponseEntity.noContent().build()
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun createUser(@Valid @RequestBody userRequestDTO: UserRequestDTO): ResponseEntity<UserResponseDTO> {
        val createdUser = userService.createUser(userRequestDTO)
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser)
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun getAllUsers(
        @PageableDefault(size = 20) pageable: Pageable
    ): ResponseEntity<Page<UserResponseDTO>> {
        val users = userService.getAllUsers(pageable)
        return ResponseEntity.ok(users)
    }

    @GetMapping("/search")
    fun searchUsers(
        @RequestParam q: String,
        @PageableDefault(size = 10) pageable: Pageable
    ): ResponseEntity<Page<PublicUserResponseDTO>> {
        return ResponseEntity.ok(userService.searchUsers(q, pageable))
    }

    @GetMapping("/{userId}")
    fun getUserById(@PathVariable userId: Long): ResponseEntity<PublicUserResponseDTO> {
        val user = userService.getUserById(userId)
        return ResponseEntity.ok(user)
    }

    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    fun getUserByEmail(@PathVariable email: String): ResponseEntity<UserResponseDTO> {
        val user = userService.getUserByEmail(email)
        return ResponseEntity.ok(user)
    }

    @GetMapping("/by-phone")
    fun getUserByPhone(
        @RequestHeader("X-Bot-Api-Key") apiKey: String,
        @RequestParam phone: String
    ): ResponseEntity<UserResponseDTO> {
        if (apiKey != botApiKey) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val user = userService.getUserByPhone(phone)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(user)
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    fun updateUser(
        @PathVariable userId: Long,
        @Valid @RequestBody sellerUpdateDTO: UserUpdateDTO
    ): ResponseEntity<UserResponseDTO> {
        val updatedUser = userService.updateUser(userId, sellerUpdateDTO)
        return ResponseEntity.ok(updatedUser)
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    fun deleteUser(@PathVariable userId: Long): ResponseEntity<Void> {
        userService.deleteUser(userId)
        return ResponseEntity.noContent().build()
    }
}
