package com.studentstorefront.dto.response

data class PublicUserResponseDTO(
    val userId: Long,
    val name: String,
    /**
     * Masked email ("j…h@constructor.university"), only filled in by the
     * signed-in buyer search so sellers can tell people with the same name
     * apart (FE-12).
     */
    val emailHint: String? = null
)
