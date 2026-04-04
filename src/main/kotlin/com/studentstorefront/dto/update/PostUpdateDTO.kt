package com.studentstorefront.dto.update

import com.studentstorefront.enums.Category
import jakarta.validation.constraints.*
import org.hibernate.validator.constraints.URL
import java.time.LocalDateTime
import java.math.BigDecimal

data class PostUpdateDTO(
    @field:Size(max = 100, message = "Title must be up to 100 characters")
    val title: String? = null,

    @field:DecimalMin(value = "0.01", message = "Price must be greater than 0")
    @field:DecimalMax(value = "999999.99", message = "Price cannot exceed 999,999.99")
    val price: BigDecimal? = null,

    @field:URL(message = "Image URL must be valid")
    val imageUrl: String? = null,

    @field:Size(min = 10, max = 1000, message = "Description must be between 10 and 1000 characters")
    val description: String? = null,

    val category: Category? = null,

    val isSold: Boolean? = null,

    val expiresAt: LocalDateTime? = null
)