package com.studentstorefront.exception

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.NoSuchElementException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentials(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("INVALID_CREDENTIALS", "Invalid email or password"))

    @ExceptionHandler(DisabledException::class)
    fun handleDisabledException(ex: DisabledException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("ACCOUNT_NOT_VERIFIED", "Please verify your email before logging in"))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDeniedException(ex: AccessDeniedException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("ACCESS_DENIED", "Access denied"))
    }

    @ExceptionHandler(DomainValidationException::class)
    fun handleDomainValidation(ex: DomainValidationException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("INVALID_REQUEST", ex.message ?: "Invalid request"))

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(error("CONFLICT", ex.message ?: "The request conflicts with the current state"))

    @ExceptionHandler(TooManyAttemptsException::class)
    fun handleTooManyAttempts(ex: TooManyAttemptsException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", ex.retryAfterSeconds.toString())
            .body(error("RATE_LIMITED", ex.message ?: "Too many attempts"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("INVALID_REQUEST", safeInvalidRequestMessage(ex.message)))
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<Map<String, Any>> {
        val details = generateSequence(ex as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        val message = when {
            details.contains("foreign key") || details.contains("still referenced") -> "This record cannot be changed because related data exists"
            details.contains("email") -> "An account with this email already exists"
            details.contains("phone_number") -> "An account with this phone number already exists"
            details.contains("rating") || details.contains("check constraint") -> "The supplied values are not valid"
            else -> "The supplied data conflicts with an existing record"
        }
        log.warn("Data integrity violation: {}", ex.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("DATA_CONFLICT", message))
    }

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLockingFailure(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            error("CONCURRENT_UPDATE", "This record changed in another request; reload and try again")
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationExceptions(ex: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> {
        val fieldErrors = ex.bindingResult.fieldErrors
            .groupBy({ it.field }, { it.defaultMessage ?: "is invalid" })
            .mapValues { (_, messages) -> messages.distinct().joinToString(", ") }
        val summary = fieldErrors.entries.joinToString(", ") { (field, message) ->
            if (message.contains(field, ignoreCase = true)) message else "$field: $message"
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorBody.of("VALIDATION_FAILED", summary.ifBlank { "Some fields are invalid" }, fieldErrors))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, HttpMessageNotReadableException::class, ServletRequestBindingException::class)
    fun handleMalformedRequest(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("MALFORMED_REQUEST", "The request could not be read"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("NOT_FOUND", "The requested resource was not found"))

    @ExceptionHandler(NoSuchElementException::class)
    fun handleMissingElement(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("NOT_FOUND", "The requested resource was not found"))

    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleResourceNotFound(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("NOT_FOUND", "The requested resource was not found"))

    @ExceptionHandler(Exception::class)
    fun handleGeneralException(ex: Exception): ResponseEntity<Map<String, Any>> {
        val correlationId = UUID.randomUUID().toString()
        log.error("Unhandled request failure correlationId={}", correlationId, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            error("INTERNAL_ERROR", "An unexpected error occurred. Reference: $correlationId")
        )
    }

    private fun error(code: String, message: String): Map<String, Any> = ErrorBody.of(code, message)

    private fun safeInvalidRequestMessage(message: String?): String {
        val value = message?.trim().orEmpty()
        return when {
            value.startsWith("Email already exists") -> "Email already exists"
            value.startsWith("Phone number already registered") -> "Phone number already registered"
            value.startsWith("Post not found") -> "Post not found"
            value.startsWith("User not found") -> "User not found"
            value.startsWith("No sale found") -> "No sale found for this post"
            value.startsWith("No account found") -> "The requested account could not be found"
            value in SAFE_MESSAGES -> value
            else -> "Invalid request"
        }
    }

    private companion object {
        val SAFE_MESSAGES = setOf(
            "At most 5 images are allowed",
            "Buyer ID is required",
            "Cover index is out of range",
            "Cover index must not be negative",
            "Description must be between 10 and 1000 characters",
            "Duplicate images are not allowed",
            "Only image uploads are supported",
            "Image must be at most 10 MB",
            "This is your own listing",
            "This sale is awaiting buyer confirmation",
            "Buyer must be a verified enabled user",
            "Current password is required for credential changes",
            "Current password is incorrect",
            "Email already exists",
            "Failed to upload image",
            "Image URL must be valid",
            "Image URL must include a host",
            "Image URL credentials are not allowed",
            "Image URL host could not be resolved",
            "Image URL host is not allowed",
            "Image URL must use HTTPS",
            "Image hashes must match the image list",
            "Invalid existing image reference",
            "Invalid image reference kind",
            "Invalid or expired reset token",
            "Invalid request",
            "Invalid uploaded image reference",
            "Invalid verification code",
            "Listing is already marked as sold",
            "Only active listings can be marked as sold",
            "Only archived or expiring listings can be renewed",
            "Password reset token has expired",
            "Phone number already registered",
            "Post not found",
            "Rating is required",
            "Reset token has already been used",
            "Reset token has expired",
            "Review already submitted for this transaction",
            "Sale ID is required",
            "Sold listings cannot be renewed",
            "Sold listings cannot be edited",
            "Sold listings cannot be deleted",
            "This account cannot be recycled",
            "This account is already verified",
            "Title is required",
            "Title must not be blank",
            "Too many image hashes",
            "Unsupported sort field",
            "Upload index is required for uploaded images",
            "User cannot select themselves as the buyer",
            "Verification code has expired",
            "Verification code is locked; request a new code"
        )
    }
}
