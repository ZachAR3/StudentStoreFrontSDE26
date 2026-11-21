package com.studentstorefront.dto.update

import com.studentstorefront.enums.Category
import jakarta.validation.constraints.*
import org.hibernate.validator.constraints.URL
import java.math.BigDecimal

data class PostUpdateDTO(
    @field:Size(max = 100, message = "Title must be up to 100 characters")
    val title: String? = null,

    @field:DecimalMin(value = "0.01", message = "Price must be greater than 0")
    @field:DecimalMax(value = "999999.99", message = "Price cannot exceed 999,999.99")
    @field:Digits(integer = 6, fraction = 2, message = "Price must have at most two decimal places")
    val price: BigDecimal? = null,

    @field:Size(max = 5, message = "At most five image URLs are allowed")
    val imageUrlList: List<@NotBlank(message = "Url images must not be blank")
    @URL(message = "Image URL must be valid") String>? = null,

    @field:Size(max = 5, message = "At most five image hashes are allowed")
    val imageHashList: List<@Pattern(regexp = "[A-Fa-f0-9]{64}", message = "Image hash must be SHA-256") String>? = null,

    @field:Size(min = 10, max = 1000, message = "Description must be between 10 and 1000 characters")
    val description: String? = null,

    val category: Category? = null,

    @field:Size(max = 5, message = "At most five images are allowed")
    val imageOrder: List<PostImageReferenceDTO>? = null
)
