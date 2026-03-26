package com.studentstorefront.dto.update

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class SellerUpdateDTO(
    @field:Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    val name: String? = null,

    @field:Email(message = "Email should be valid")
    @field:Pattern(
        regexp = ".*@constructor\\.university$",
        message = "Email must end with @constructor.university"
    )
    val email: String? = null,

    @field:Pattern(
        regexp = "^\\+?[0-9]{10,15}$",
        message = "Phone number must contain 10–15 digits and may start with +"
    )
    val phoneNumber: String? = null,

    @field:Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
        message = "Password must contain at least 8 characters with uppercase, lowercase, digit, and special character"
    )
    val password: String? = null
)