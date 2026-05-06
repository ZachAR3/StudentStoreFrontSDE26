package com.studentstorefront.config

import com.studentstorefront.entity.Seller
import com.studentstorefront.repository.SellerRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

@Configuration
class DataLoader {

    @Bean
    fun initData(sellerRepository: SellerRepository, jdbcTemplate: JdbcTemplate): CommandLineRunner {
        return CommandLineRunner {
            val repairedPosts = jdbcTemplate.update("UPDATE posts SET status = 'ACTIVE' WHERE status IS NULL")
            if (repairedPosts > 0) {
                println("Repaired $repairedPosts post(s) with missing status")
            }

            if (sellerRepository.count() == 0L) {
                val defaultSeller = Seller(
                    name = "Demo Seller",
                    email = "demo@student.university",
                    phoneNumber = "+1234567890",
                    password = "password" // In a real app, this should be encoded
                )
                sellerRepository.save(defaultSeller)
                println("Default seller created with ID: ${defaultSeller.sellerId}")
            }
        }
    }
}
