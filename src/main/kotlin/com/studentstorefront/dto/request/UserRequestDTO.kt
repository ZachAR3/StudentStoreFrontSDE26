package com.studentstorefront.dto.request

import com.studentstorefront.validation.PASSWORD_MESSAGE
import com.studentstorefront.validation.PASSWORD_REGEX
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class UserRequestDTO(
    @field:NotBlank(message = "Name is required")
    @field:Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    val name: String,

    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Email should be valid")
    @field:Pattern(
        regexp = ".*@constructor\\.university$",
        message = "Email must end with @constructor.university"
    )
    val email: String,

    @field:NotBlank(message = "Phone number is required")
    @field:Pattern(
        regexp = "^\\+?[0-9]{10,15}$",
        message = "Phone number must contain 10–15 digits and may start with +"
    )
    val phoneNumber: String,

    @field:NotBlank(message = "Password is required")
    @field:Pattern(
        regexp = PASSWORD_REGEX,
        message = PASSWORD_MESSAGE
    )
    val password: String
)
