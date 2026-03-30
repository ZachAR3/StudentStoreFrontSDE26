package com.studentstorefront.service

import com.studentstorefront.repository.SellerRepository
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/**
 * Custom implementation of Spring Security's UserDetailsService.
 * This service is responsible for loading user-specific data during authentication.
 */
@Service
class CustomUserDetailsService(
    private val sellerRepository: SellerRepository
) : UserDetailsService {

    override fun loadUserByUsername(email: String): UserDetails {
        val seller = sellerRepository.findByEmail(email)
            ?: throw UsernameNotFoundException("User not found with email: $email")

        return User.builder()
            .username(seller.email)
            .password(seller.password)
            .authorities("ROLE_${seller.role.name}")
            .accountExpired(false)
            .accountLocked(false)
            .credentialsExpired(false)
            .disabled(!seller.isEnabled)
            .build()
    }
}