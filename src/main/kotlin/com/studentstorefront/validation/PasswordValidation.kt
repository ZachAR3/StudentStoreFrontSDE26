package com.studentstorefront.validation

const val PASSWORD_REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d\\s])\\S{8,}$"
const val PASSWORD_MESSAGE =
    "Password must contain at least 8 characters with uppercase, lowercase, digit, and special character, and cannot contain spaces"
