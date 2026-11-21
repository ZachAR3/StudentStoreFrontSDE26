package com.studentstorefront.service

import java.net.URI

/**
 * Listing images may only come from our own media host (BE-10). Any other
 * HTTPS host would let a listing embed third-party tracking pixels. Matching
 * on the host name also removes the old request-time DNS lookup and its
 * check-then-use race.
 *
 * Override with MEDIA_ALLOWED_HOSTS (comma-separated) if images move to a
 * custom delivery domain.
 */
object MediaUrlPolicy {
    private val allowedHosts: Set<String> =
        (System.getenv("MEDIA_ALLOWED_HOSTS") ?: System.getProperty("media.allowed-hosts") ?: DEFAULT_HOSTS)
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun validate(url: String): String {
        val normalized = url.trim()
        val uri = runCatching { URI(normalized) }
            .getOrElse { throw IllegalArgumentException("Image URL must be valid") }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("Image URL must include a host")
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Image URL must use HTTPS"
        }
        require(uri.userInfo == null) { "Image URL credentials are not allowed" }
        require(uri.port == -1 || uri.port == 443) { "Image URL host is not allowed" }
        require(host in allowedHosts) { "Image URL host is not allowed" }
        return normalized
    }

    private const val DEFAULT_HOSTS = "res.cloudinary.com"
}
