package com.studentstorefront.service

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import com.studentstorefront.entity.User
import com.studentstorefront.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.*
import javax.crypto.SecretKey


@Service
class JwtService(
    private val clock: Clock = Clock.systemUTC()
) {

    private val generatedFallbackKey: SecretKey = Jwts.SIG.HS256.key().build()

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var userRepository: UserRepository

    @Value("\${jwt.secret}")
    private lateinit var secretKey: String

    @Value("\${jwt.expiration:86400000}")
    private var jwtExpiration: Long = 86400000

    fun generateToken(userDetails: UserDetails): String {
        val user = userRepository.findByEmail(IdentityNormalizer.email(userDetails.username))
            ?: throw IllegalArgumentException("Authenticated user no longer exists")
        return generateToken(user)
    }

    fun generateToken(extraClaims: Map<String, Any>, userDetails: UserDetails): String {
        val user = userRepository.findByEmail(IdentityNormalizer.email(userDetails.username))
            ?: throw IllegalArgumentException("Authenticated user no longer exists")
        return buildToken(extraClaims, user, jwtExpiration)
    }

    fun generateToken(user: User): String = buildToken(emptyMap(), user, jwtExpiration)

    private fun buildToken(
        extraClaims: Map<String, Any>,
        user: User,
        expiration: Long
    ): String {
        val issuedAt = Date.from(clock.instant())
        return Jwts.builder()
            .claims(extraClaims + mapOf("uid" to (user.userId ?: throw IllegalArgumentException("User has no id")), "ver" to user.tokenVersion))
            .subject(user.email)
            .issuedAt(issuedAt)
            .expiration(Date(issuedAt.time + expiration))
            .signWith(getSignInKey()) // Algo HS256
            .compact()
    }

    fun isTokenValid(token: String, user: User, userDetails: UserDetails): Boolean {
        val username = extractUsername(token)
        val userId = extractUserId(token)
        val tokenVersion = extractTokenVersion(token)
        return user.isEnabled &&
            username == user.email &&
            userId == user.userId &&
            tokenVersion == user.tokenVersion &&
            userDetails.isAccountNonExpired &&
            userDetails.isAccountNonLocked &&
            userDetails.isCredentialsNonExpired &&
            userDetails.isEnabled &&
            !isTokenExpired(token)
    }

    fun extractUserId(token: String): Long? = extractClaim(token) { it["uid"]?.toString()?.toLongOrNull() }

    private fun extractTokenVersion(token: String): Long? = extractClaim(token) { it["ver"]?.toString()?.toLongOrNull() }

    fun extractUsername(token: String): String? {
        return extractClaim(token) { it.subject }
    }

    fun <T> extractClaim(token: String, claimsResolver: (Claims) -> T): T {
        val claims = extractAllClaims(token)
        return claimsResolver(claims)
    }

    private fun isTokenExpired(token: String): Boolean {
        return extractExpiration(token).before(Date.from(clock.instant()))
    }

    private fun extractExpiration(token: String): Date {
        return extractClaim(token) { it.expiration }
    }

    private fun extractAllClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(getSignInKey())
            .build()
            .parseSignedClaims(token)
            .payload
    }

    private fun getSignInKey(): SecretKey {
        if (secretKey.isBlank()) return generatedFallbackKey
        val decodedKey = runCatching { Decoders.BASE64.decode(secretKey) }.getOrNull()
        val keyBytes = decodedKey?.takeIf { it.size >= 32 }
            ?: secretKey.toByteArray(Charsets.UTF_8)
        return Keys.hmacShaKeyFor(keyBytes)
    }
}
