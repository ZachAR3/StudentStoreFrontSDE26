package com.studentstorefront.controller

import com.studentstorefront.dto.request.ForgotPasswordRequestDTO
import com.studentstorefront.dto.request.LoginRequestDTO
import com.studentstorefront.dto.request.ResendVerificationRequestDTO
import com.studentstorefront.dto.request.ResetPasswordRequestDTO
import com.studentstorefront.dto.request.UserRequestDTO
import com.studentstorefront.dto.request.VerifyEmailRequestDTO
import com.studentstorefront.dto.response.AuthResponseDTO
import com.studentstorefront.dto.response.UserResponseDTO
import com.studentstorefront.dto.response.VerificationRequiredResponseDTO
import com.studentstorefront.service.EmailVerificationService
import com.studentstorefront.exception.TooManyAttemptsException
import com.studentstorefront.service.JwtService
import com.studentstorefront.service.LoginAttemptService
import com.studentstorefront.service.PasswordResetService
import com.studentstorefront.service.UserService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val jwtService: JwtService,
    private val userService: UserService,
    private val userDetailsService: UserDetailsService,
    private val passwordResetService: PasswordResetService,
    private val emailVerificationService: EmailVerificationService,
    private val loginAttemptService: LoginAttemptService
) {

    /**
     * Self-service registration endpoint
     * Creates a new user account and sends an email verification code.
     */
    @PostMapping("/register")
    fun register(@Valid @RequestBody registerRequest: UserRequestDTO): ResponseEntity<Any> {
        val email = userService.register(registerRequest)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            VerificationRequiredResponseDTO(email = email)
        )
    }

    @PostMapping("/verify-email")
    fun verifyEmail(@Valid @RequestBody request: VerifyEmailRequestDTO): ResponseEntity<AuthResponseDTO> {
        val verifiedUser = emailVerificationService.verifyCode(request.email, request.code)
        val userDetails = userDetailsService.loadUserByUsername(verifiedUser.email)
        val token = jwtService.generateToken(userDetails)
        val user = userService.getUserByEmail(verifiedUser.email)
        return ResponseEntity.ok(AuthResponseDTO(token = token, user = user))
    }

    @PostMapping("/resend-verification")
    fun resendVerification(@Valid @RequestBody request: ResendVerificationRequestDTO): ResponseEntity<Map<String, String>> {
        emailVerificationService.resendCode(request.email)
        return ResponseEntity.ok(mapOf("message" to "If that account is waiting for verification, a new code has been sent"))
    }

    /**
     * Login endpoint for existing users
     * Authenticates user and returns JWT token
     */
    @PostMapping("/login")
    fun login(@Valid @RequestBody loginRequest: LoginRequestDTO): ResponseEntity<AuthResponseDTO> {
        loginAttemptService.lockedForSeconds(loginRequest.email)?.let { seconds ->
            throw TooManyAttemptsException(seconds)
        }
        val authentication = try {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken(loginRequest.email, loginRequest.password)
            )
        } catch (ex: org.springframework.security.authentication.BadCredentialsException) {
            loginAttemptService.recordFailure(loginRequest.email)
            throw ex
        }
        loginAttemptService.recordSuccess(loginRequest.email)

        // Get user details from authentication
        val userDetails = authentication.principal as org.springframework.security.core.userdetails.UserDetails

        // Generate JWT token
        val token = jwtService.generateToken(userDetails)

        // Get user information
        val user = userService.getUserByEmail(loginRequest.email)

        // Return token with user info
        val authResponse = AuthResponseDTO(
            token = token,
            user = user
        )

        return ResponseEntity.ok(authResponse)
    }

    @GetMapping("/me")
    fun getMe(): ResponseEntity<UserResponseDTO> = ResponseEntity.ok(userService.getMe())

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
