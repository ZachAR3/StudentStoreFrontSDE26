package com.studentstorefront.controller

import com.studentstorefront.dto.request.ForgotPasswordRequestDTO
import com.studentstorefront.dto.request.LoginRequestDTO
import com.studentstorefront.dto.request.ResetPasswordRequestDTO
import com.studentstorefront.dto.request.SellerRequestDTO
import com.studentstorefront.dto.response.AuthResponseDTO
import com.studentstorefront.service.JwtService
import com.studentstorefront.service.PasswordResetService
import com.studentstorefront.service.SellerService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = ["http://localhost:8080", "http://127.0.0.1:8080", "http://localhost:3000"])
class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val jwtService: JwtService,
    private val sellerService: SellerService,
    private val userDetailsService: UserDetailsService,
    private val passwordResetService: PasswordResetService
) {

    @PostMapping("/register")
    fun register(@Valid @RequestBody registerRequest: SellerRequestDTO): ResponseEntity<Map<String, String>> {
        sellerService.createSeller(registerRequest)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(mapOf("message" to "Registration successful. Check your email to verify your account."))
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody loginRequest: LoginRequestDTO): ResponseEntity<*> {
        val authentication = try {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken(loginRequest.email, loginRequest.password)
            )
        } catch (e: DisabledException) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("message" to "Account not verified. Check your email for the verification link."))
        }

        val userDetails = authentication.principal as org.springframework.security.core.userdetails.UserDetails
        val token = jwtService.generateToken(userDetails)
        val seller = sellerService.getSellerByEmail(loginRequest.email)

        return ResponseEntity.ok(AuthResponseDTO(token = token, seller = seller))
    }

    @GetMapping("/verify-email")
    fun verifyEmail(@RequestParam token: String): ResponseEntity<Map<String, String>> {
        sellerService.verifyEmail(token)
        return ResponseEntity.ok(mapOf("message" to "Email verified. You can now log in."))
    }

    @PostMapping("/forgot-password")
    fun forgotPassword(@Valid @RequestBody request: ForgotPasswordRequestDTO): ResponseEntity<Map<String, String>> {
        passwordResetService.requestReset(request.email)
        return ResponseEntity.ok(mapOf("message" to "If that email exists, a reset link has been sent"))
    }

    @PostMapping("/reset-password")
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequestDTO): ResponseEntity<Map<String, String>> {
        passwordResetService.resetPassword(request.token, request.newPassword)
        return ResponseEntity.ok(mapOf("message" to "Password reset successfully"))
    }
}