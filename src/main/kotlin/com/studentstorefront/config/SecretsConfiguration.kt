package com.studentstorefront.config

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

@Configuration
class SecretsConfiguration(
    @Value("\${jwt.secret:}") private val jwtSecret: String,
    @Value("\${whatsapp.bot.api-key:}") private val botApiKey: String,
    @Value("\${app.require-secure-secrets:false}") private val requireSecureSecrets: Boolean,
    @Value("\${spring.profiles.active:}") private val activeProfiles: String,
    @Value("\${spring.datasource.username:}") private val databaseUsername: String,
    @Value("\${spring.datasource.password:}") private val databasePassword: String,
    @Value("\${FLYWAY_USERNAME:}") private val flywayUsername: String,
    @Value("\${FLYWAY_PASSWORD:}") private val flywayPassword: String,
    @Value("\${cloudinary.cloud-name:}") private val cloudinaryCloudName: String,
    @Value("\${cloudinary.api-key:}") private val cloudinaryApiKey: String,
    @Value("\${cloudinary.api-secret:}") private val cloudinaryApiSecret: String
) {
    @PostConstruct
    fun validate() {
        val profiles = activeProfiles.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val production = profiles.any { it in setOf("prod", "production") }
        val developmentOnly = profiles.isNotEmpty() && profiles.all { it in DEVELOPMENT_PROFILES }

        // A blank JWT_SECRET means a random per-process key: every restart logs
        // everyone out and instances reject each other's tokens. Only the
        // local and test profiles may run like that (BE-06).
        if (!developmentOnly) {
            require(isStrongSecret(jwtSecret)) {
                "JWT_SECRET must be set to a high-entropy value (32+ characters) unless the profile is local or test"
            }
        } else if (jwtSecret.isBlank()) {
            log.warn("JWT_SECRET is blank; using a random per-process key (logins reset on restart)")
        }
        if (!production && !requireSecureSecrets) return

        require(isStrongSecret(jwtSecret)) {
            "JWT_SECRET must be configured with a high-entropy value in production"
        }
        require(isStrongSecret(botApiKey)) {
            "BOT_API_KEY must be configured with a high-entropy value in production"
        }
        require(databaseUsername.isNotBlank() && isStrongSecret(databasePassword)) {
            "DB_USERNAME and DB_PASSWORD must be configured with non-placeholder values in production"
        }
        require(flywayUsername.isNotBlank() && isStrongSecret(flywayPassword)) {
            "FLYWAY_USERNAME and FLYWAY_PASSWORD must be configured with non-placeholder values in production"
        }
        require(isConfigured(cloudinaryCloudName) && isConfigured(cloudinaryApiKey) && isConfigured(cloudinaryApiSecret)) {
            "CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, and CLOUDINARY_API_SECRET must be configured in production"
        }
    }

    private companion object {
        val DEVELOPMENT_PROFILES = setOf("local", "test", "dev")
        val log = org.slf4j.LoggerFactory.getLogger(SecretsConfiguration::class.java)
    }

    private fun isStrongSecret(value: String): Boolean =
        value.length >= 32 &&
            value.none { it.isWhitespace() } &&
            value.lowercase() !in setOf("changeme", "change_this", "secret", "password")

    private fun isConfigured(value: String): Boolean =
        value.isNotBlank() && value.lowercase() !in setOf("changeme", "change_this", "replace-me", "replace_with_a_value")
}
