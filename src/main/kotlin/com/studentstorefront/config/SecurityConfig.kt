package com.studentstorefront.config

import com.studentstorefront.exception.ErrorBody
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    @org.springframework.beans.factory.annotation.Value("\${app.base-url:http://localhost:8080}") private val appBaseUrl: String,
    @org.springframework.beans.factory.annotation.Value("\${app.cors.extra-origins:}") private val extraCorsOrigins: String
) {

    /**
     * The SPA is served from the same origin as the API, so CORS is only for
     * the exact public origin plus any extra origins configured explicitly
     * (BE-13). Wildcards such as *.duckdns.org are not allowed.
     */
    @Bean
    fun corsConfigurationSource(): org.springframework.web.cors.CorsConfigurationSource {
        val origins = (listOf(appBaseUrl) + extraCorsOrigins.split(','))
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()
        val config = org.springframework.web.cors.CorsConfiguration().apply {
            allowedOrigins = origins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type")
            maxAge = 3600
        }
        return org.springframework.web.cors.UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/**", config)
        }
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    // Public API endpoints
                    .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/posts/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/posts/bot", "/api/posts/bot/**").permitAll() // Bot endpoints authenticate with their scoped key
                    .requestMatchers(HttpMethod.GET, "/api/posts/bot/media/resolve").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/users/by-phone").permitAll() // Bot endpoint authenticates with its scoped key
                    
                    // Static resources (Frontend)
                    .requestMatchers("/", "/index.html", "/static/**", "/css/**", "/js/**", "/config/**", "/favicon.ico").permitAll()
                    
                    // Documentation
                    .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    
                    // All other requests require authentication
                    .anyRequest().authenticated()
            }
            .exceptionHandling { ex ->
                ex.authenticationEntryPoint { _, response, _ ->
                    ErrorBody.write(response, HttpServletResponse.SC_UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication required")
                }
                ex.accessDeniedHandler { _, response, _ ->
                    ErrorBody.write(response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "Access denied")
                }
            }
            .cors { }
            .headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives(CONTENT_SECURITY_POLICY) }
            }
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    private companion object {
        /**
         * FE-14. Alpine's standard build evaluates expressions with Function(),
         * hence 'unsafe-eval'; inline style attributes need 'unsafe-inline' for
         * styles only. Listing images come from Cloudinary (https:), upload
         * previews use blob:, and the QR code comes from api.qrserver.com.
         */
        const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; " +
                "script-src 'self' 'unsafe-eval' https://cdn.jsdelivr.net; " +
                "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                "img-src 'self' https: data: blob:; " +
                "connect-src 'self'; " +
                "font-src 'self' data: https://cdn.jsdelivr.net; " +
                "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }

    @Bean
    fun authenticationManager(config: AuthenticationConfiguration): AuthenticationManager {
        return config.authenticationManager
    }
}
