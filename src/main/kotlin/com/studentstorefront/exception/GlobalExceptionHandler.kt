package com.studentstorefront.exception

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.DisabledException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(DisabledException::class)
    fun handleDisabledException(ex: DisabledException): ResponseEntity<Map<String, String>> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("message" to "Please verify your email before logging in"))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDeniedException(ex: AccessDeniedException): ResponseEntity<Map<String, String>> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("message" to (ex.message ?: "Access denied")))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("message" to (ex.message ?: "Invalid request")))
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<Map<String, String>> {
        val details = generateSequence(ex as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        val message = when {
            details.contains("violates foreign key constraint") || details.contains("still referenced") ->
                "This record can't be deleted because other data still references it"
            details.contains("email") -> "An account with this email already exists"
            details.contains("phone_number") -> "An account with this phone number already exists"
            else -> "A record with this data already exists"
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("message" to message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationExceptions(ex: MethodArgumentNotValidException): ResponseEntity<Map<String, String>> {
        val errors = ex.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("message" to errors))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneralException(ex: Exception): ResponseEntity<Map<String, String>> {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("message" to (ex.message ?: "An unexpected error occurred")))
    }
}
