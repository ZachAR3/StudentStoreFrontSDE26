package com.studentstorefront.dto.request

import com.studentstorefront.entity.Category
import jakarta.validation.constraints.*
import org.hibernate.validator.constraints.URL
import java.time.LocalDateTime
import java.math.BigDecimal

data class PostRequestDTO(
    @field:NotBlank(message = "Title is required")
    @field:Size(max = 100, message = "Title must be up to 100 characters")
    val title: String,

    @field:NotNull(message = "Price is required")
    @field:DecimalMin(value = "0.01", message = "Price must be greater than 0")
    @field:DecimalMax(value = "999999.99", message = "Price cannot exceed 999,999.99")
    val price: BigDecimal,

    @field:NotBlank(message = "Image URL is required")
    @field:URL(message = "Image URL must be valid")
    val imageUrl: String,

    @field:NotBlank(message = "Description is required")
    @field:Size(min = 10, max = 1000, message = "Description must be between 10 and 1000 characters")
    val description: String,

    @field:NotNull(message = "Category is required")
    val category: Category,

    val isSold: Boolean? = false,

    val expiresAt: LocalDateTime? = null,

    @field:NotNull(message = "Seller ID is required")
    val sellerId: Long?
)