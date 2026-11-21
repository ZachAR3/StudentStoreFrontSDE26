package com.studentstorefront.service

data class UserRegisteredEvent(
    val userId: Long,
    val phoneNumber: String,
    val registrationVersion: Long
)
