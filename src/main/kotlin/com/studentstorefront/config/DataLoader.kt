package com.studentstorefront.config

import com.studentstorefront.entity.User
import com.studentstorefront.repository.UserRepository
import com.studentstorefront.service.IdentityNormalizer
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.slf4j.LoggerFactory

@Configuration
@Profile("local")
class DataLoader {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun initData(
        userRepository: UserRepository,
        passwordEncoder: PasswordEncoder
    ): CommandLineRunner {
        return CommandLineRunner {
            val emailInput = System.getenv("DEMO_USER_EMAIL")?.trim().orEmpty()
            val phoneInput = System.getenv("DEMO_USER_PHONE")?.trim().orEmpty()
            val email = emailInput.let(IdentityNormalizer::email)
            val phone = phoneInput.let(IdentityNormalizer::phone)
            val password = System.getenv("DEMO_USER_PASSWORD").orEmpty()
            if (emailInput.isBlank() || phoneInput.isBlank() || password.isBlank()) {
                log.info("Demo user bootstrap skipped; DEMO_USER_EMAIL, DEMO_USER_PHONE, and DEMO_USER_PASSWORD are required")
                return@CommandLineRunner
            }
            if (userRepository.findByEmail(email) != null) return@CommandLineRunner

            val demoUser = User(
                name = "Demo User",
                email = email,
                phoneNumber = phone,
                password = passwordEncoder.encode(password)!!,
                isEnabled = true,
                registrationPending = false
            )
            userRepository.save(demoUser)
            log.info("Configured demo user created with ID {}", demoUser.userId)
        }
    }
}
