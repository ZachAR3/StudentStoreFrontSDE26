package com.studentstorefront.config

import com.studentstorefront.entity.Seller
import com.studentstorefront.repository.SellerRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration
class DataLoader {

    @Bean
    fun initData(
        sellerRepository: SellerRepository,
        jdbcTemplate: JdbcTemplate,
        passwordEncoder: PasswordEncoder
    ): CommandLineRunner {
        return CommandLineRunner {
            val repairedPosts = jdbcTemplate.update("UPDATE posts SET status = 'ACTIVE' WHERE status IS NULL")
            if (repairedPosts > 0) {
                println("Repaired $repairedPosts post(s) with missing status")
            }

            if (sellerRepository.count() == 0L) {
                val defaultSeller = Seller(
                    name = "Demo Seller",
                    email = "demo@constructor.university",
                    phoneNumber = "+1234567890",
                    password = passwordEncoder.encode("password") ?: ""
                )
                sellerRepository.save(defaultSeller)
                println("Default seller created with ID: ${defaultSeller.sellerId}")
            }
        }
    }
}
