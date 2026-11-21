package com.studentstorefront.exception

import jakarta.servlet.http.HttpServletResponse

/**
 * The single error envelope used by controllers, security handlers and
 * servlet filters: `{"code": ..., "message": ..., "fieldErrors": {...}}`.
 * `fieldErrors` is only present for validation failures.
 */
object ErrorBody {
    fun of(code: String, message: String, fieldErrors: Map<String, String>? = null): Map<String, Any> =
        buildMap {
            put("code", code)
            put("message", message)
            if (!fieldErrors.isNullOrEmpty()) put("fieldErrors", fieldErrors)
        }

    /** Writes the envelope from filters, which run outside the controller advice. */
    fun write(response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"code":"${escape(code)}","message":"${escape(message)}"}""")
    }

    private fun escape(value: String): String = buildString {
        value.forEach { ch ->
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < ' ' -> append(String.format("\\u%04x", ch.code))
                else -> append(ch)
            }
        }
    }
}
