package com.studentstorefront.config

import com.studentstorefront.entity.Seller
import com.studentstorefront.repository.SellerRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DataLoader {

    @Bean
    fun initData(sellerRepository: SellerRepository): CommandLineRunner {
        return CommandLineRunner {
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
