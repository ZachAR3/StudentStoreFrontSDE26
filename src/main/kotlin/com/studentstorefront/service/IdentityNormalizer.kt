package com.studentstorefront.service

import java.util.Locale

object IdentityNormalizer {
    fun email(value: String): String = value.trim().lowercase(Locale.ROOT)

    fun phone(value: String): String = "+" + value.filter(Char::isDigit)
}
