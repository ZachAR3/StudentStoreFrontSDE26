package com.studentstorefront.controller

import com.studentstorefront.entity.Post
import com.studentstorefront.entity.Seller
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.SellerRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDateTime

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@Transactional
@TestPropertySource(properties = [
    "whatsapp.bot.api-key=test-secret-key",
    "whatsapp.bot.phone=15551234567"
])
class PostSearchControllerTest {

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:15")

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var webApplicationContext: WebApplicationContext
    @Autowired lateinit var sellerRepository: SellerRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private lateinit var mockMvc: MockMvc
    private lateinit var seller: Seller

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        seller = sellerRepository.save(
            Seller(
                name = "Search Seller",
                email = "search@constructor.university",
                phoneNumber = "15550001111",
                password = passwordEncoder.encode("password") ?: "",
                role = Role.SELLER
            )
        )

        postRepository.saveAll(
            listOf(
                post("Standing Desk", "Adjustable dorm desk", Category.FURNITURE, createdAt = LocalDateTime.now().minusHours(1)),
                post("Desk Lamp", "Bright lamp for late study sessions", Category.ELECTRONICS, createdAt = LocalDateTime.now().minusHours(2)),
                post("Calculus Book", "Desk copy with notes", Category.BOOKS, createdAt = LocalDateTime.now().minusHours(3)),
                post("Sold Desk Chair", "Already gone", Category.FURNITURE, isSold = true, createdAt = LocalDateTime.now())
            )
        )
    }

    @Test
    fun `search returns only unsold posts matching title or description`() {
        mockMvc.perform(get("/api/posts/search").param("q", "desk").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.content[?(@.title == 'Sold Desk Chair')]").doesNotExist())
    }

    @Test
    fun `search can filter by category`() {
        mockMvc.perform(
            get("/api/posts/search")
                .param("q", "desk")
                .param("category", "furniture")
                .param("size", "10")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Standing Desk"))
    }

    @Test
    fun `search returns bad request for invalid category`() {
        mockMvc.perform(get("/api/posts/search").param("category", "cars"))
            .andExpect(status().isBadRequest)
    }

    private fun post(
        title: String,
        description: String,
        category: Category,
        isSold: Boolean = false,
        createdAt: LocalDateTime
    ): Post {
        return Post(
            title = title,
            price = BigDecimal("10.00"),
            description = description,
            category = category,
            isSold = isSold,
            createdAt = createdAt,
            seller = seller
        )
    }
}
