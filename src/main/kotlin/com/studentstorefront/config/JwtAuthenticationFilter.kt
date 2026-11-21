package com.studentstorefront.config

import com.studentstorefront.service.JwtService
import com.studentstorefront.repository.UserRepository
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val userDetailsService: UserDetailsService,
    private val userRepository: UserRepository
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authHeader = request.getHeader("Authorization")

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response)
            return
        }

        val jwt = authHeader.substring(7)

        try {
            val userId = jwtService.extractUserId(jwt)

            if (userId != null && SecurityContextHolder.getContext().authentication == null) {
                val user = userRepository.findById(userId).orElse(null)
                    ?: run {
                        sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid token")
                        return
                    }
                val userDetails = userDetailsService.loadUserByUsername(user.email)

                if (jwtService.isTokenValid(jwt, user, userDetails)) {
                    val authToken = UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.authorities
                    )
                    authToken.details = WebAuthenticationDetailsSource().buildDetails(request)
                    SecurityContextHolder.getContext().authentication = authToken
                }
            }
        } catch (ex: ExpiredJwtException) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Token has expired")
            return
        } catch (ex: JwtException) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid token")
            return
        } catch (ex: Exception) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed")
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun sendErrorResponse(response: HttpServletResponse, status: Int, message: String) {
        com.studentstorefront.exception.ErrorBody.write(response, status, "AUTHENTICATION_FAILED", message)
    }
}
