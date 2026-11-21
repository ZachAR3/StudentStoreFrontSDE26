package com.studentstorefront.service

import java.time.LocalDateTime

data class PhoneVerifiedEvent(
    val userId: Long,
    val phoneNumber: String,
    val verifiedAt: LocalDateTime
)
