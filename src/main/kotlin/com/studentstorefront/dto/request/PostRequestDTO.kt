package com.studentstorefront.dto.request

import com.studentstorefront.enums.Category
import jakarta.validation.constraints.*
import org.hibernate.validator.constraints.URL
import java.math.BigDecimal

data class PostRequestDTO(
    @field:NotBlank(message = "Title is required")
    @field:Size(max = 100, message = "Title must be up to 100 characters")
    val title: String,

    @field:NotNull(message = "Price is required")
    @field:DecimalMin(value = "0.01", message = "Price must be greater than 0")
    @field:DecimalMax(value = "999999.99", message = "Price cannot exceed 999,999.99")
    @field:Digits(integer = 6, fraction = 2, message = "Price must have at most two decimal places")
    val price: BigDecimal,

    @field:Size(max = 5, message = "At most five images are allowed")
    val imageUrlList: List<@NotBlank(message = "Url images must not be blank")
    @URL(message = "Image URL must be valid") String>? = emptyList(),

    @field:Size(max = 5, message = "At most five image hashes are allowed")
    val imageHashList: List<@Pattern(regexp = "[A-Fa-f0-9]{64}", message = "Image hash must be SHA-256") String> = emptyList(),

    @field:NotBlank(message = "Description is required")
    @field:Size(min = 10, max = 1000, message = "Description must be between 10 and 1000 characters")
    val description: String,

    @field:NotNull(message = "Category is required")
    val category: Category,

    val userId: Long? = null,

    @field:Size(max = 128, message = "Idempotency key is too long")
    val idempotencyKey: String? = null
)
