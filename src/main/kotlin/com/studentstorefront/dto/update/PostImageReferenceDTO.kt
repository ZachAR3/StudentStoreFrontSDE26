package com.studentstorefront.dto.update

data class PostImageReferenceDTO(
    val kind: String,
    val url: String? = null,
    val uploadIndex: Int? = null
)
