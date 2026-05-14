package com.studentstorefront.config

import com.studentstorefront.entity.User
import com.studentstorefront.repository.UserRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration
@Profile("local")
class DataLoader {

    @Bean
    fun initData(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoder
    ): CommandLineRunner {
        return CommandLineRunner {
            if (userRepository.count() == 0L) {
                val defaultSeller = User(
                    name = "Demo User",
                    email = "demo@constructor.university",
                    phoneNumber = "+1234567890",
                    password = passwordEncoder.encode("password") ?: ""
                )
                userRepository.save(defaultSeller)
                println("Default user created with ID: ${defaultSeller.userId}")
            }
        }
    }
}
