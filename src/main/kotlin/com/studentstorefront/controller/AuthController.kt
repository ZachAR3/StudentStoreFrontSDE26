package com.studentstorefront.controller

import com.studentstorefront.dto.request.ForgotPasswordRequestDTO
import com.studentstorefront.dto.request.LoginRequestDTO
import com.studentstorefront.dto.request.ResendVerificationRequestDTO
import com.studentstorefront.dto.request.ResetPasswordRequestDTO
import com.studentstorefront.dto.request.SellerRequestDTO
import com.studentstorefront.dto.request.VerifyEmailRequestDTO
import com.studentstorefront.dto.response.AuthResponseDTO
import com.studentstorefront.dto.response.SellerResponseDTO
import com.studentstorefront.dto.response.VerificationRequiredResponseDTO
import com.studentstorefront.service.EmailService
import com.studentstorefront.service.EmailVerificationService
import com.studentstorefront.service.JwtService
import com.studentstorefront.service.PasswordResetService
import com.studentstorefront.service.SellerService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
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
    private val passwordResetService: PasswordResetService,
    private val emailVerificationService: EmailVerificationService,
    private val emailService: EmailService
) {

    /**
     * Self-service registration endpoint
     * Creates a new seller account and returns JWT token immediately
     */
    @PostMapping("/register")
    fun register(@Valid @RequestBody registerRequest: SellerRequestDTO): ResponseEntity<VerificationRequiredResponseDTO> {
        val (createdSeller, code) = sellerService.createSellerWithToken(registerRequest)
        emailService.sendVerificationEmail(createdSeller.email, code)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            VerificationRequiredResponseDTO(email = createdSeller.email)
        )
    }

    @PostMapping("/verify-email")
    fun verifyEmail(@Valid @RequestBody request: VerifyEmailRequestDTO): ResponseEntity<AuthResponseDTO> {
        emailVerificationService.verifyCode(request.email, request.code)
        val userDetails = userDetailsService.loadUserByUsername(request.email)
        val token = jwtService.generateToken(userDetails)
        val seller = sellerService.getSellerByEmail(request.email)
        return ResponseEntity.ok(AuthResponseDTO(token = token, seller = seller))
    }

    @PostMapping("/resend-verification")
    fun resendVerification(@Valid @RequestBody request: ResendVerificationRequestDTO): ResponseEntity<Map<String, String>> {
        emailVerificationService.resendCode(request.email)
        return ResponseEntity.ok(mapOf("message" to "Verification code resent"))
    }

    /**
     * Login endpoint for existing users
     * Authenticates user and returns JWT token
     */
    @PostMapping("/login")
    fun login(@Valid @RequestBody loginRequest: LoginRequestDTO): ResponseEntity<AuthResponseDTO> {
        // Authenticate user
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(loginRequest.email, loginRequest.password)
        )

        // Get user details from authentication
        val userDetails = authentication.principal as org.springframework.security.core.userdetails.UserDetails

        // Generate JWT token
        val token = jwtService.generateToken(userDetails)

        // Get seller information
        val seller = sellerService.getSellerByEmail(loginRequest.email)

        // Return token with seller info
        val authResponse = AuthResponseDTO(
            token = token,
            seller = seller
        )

        return ResponseEntity.ok(authResponse)
    }

    @GetMapping("/me")
    fun getMe(): ResponseEntity<SellerResponseDTO> = ResponseEntity.ok(sellerService.getMe())

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