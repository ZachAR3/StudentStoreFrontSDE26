package com.studentstorefront.dto.update

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Size

data class SellerUpdateDTO(
    @field:Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    val name: String? = null,

    @field:Email(message = "Email should be valid")
    val email: String? = null,

    @field:Size(min = 10, max = 15, message = "Phone number must be between 10 and 15 characters")
    val phoneNumber: String? = null,

    @field:Size(min = 6, message = "Password must be at least 6 characters")
    val password: String? = null
)