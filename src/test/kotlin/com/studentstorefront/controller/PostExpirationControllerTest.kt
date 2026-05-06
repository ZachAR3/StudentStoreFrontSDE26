package com.studentstorefront.controller

import com.studentstorefront.entity.Post
import com.studentstorefront.entity.Seller
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.SellerRepository
import org.junit.jupiter.api.Assertions.*
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
    "whatsapp.bot.phone=15551234567",
    "whatsapp.bot.api-url=http://localhost:3000"
])
class PostExpirationControllerTest {

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

        seller = sellerRepository.save(Seller(
            name = "Expiry Seller",
            email = "expiry@constructor.university",
            phoneNumber = "15550002222",
            password = passwordEncoder.encode("password") ?: "",
            role = Role.SELLER
        ))
    }

    // ── visibility rules ───────────────────────────────────────────────────────

    @Test
    fun `archived posts do not appear in search results`() {
        postRepository.saveAll(listOf(
            buildPost("Active Laptop", PostStatus.ACTIVE),
            buildPost("Archived Phone", PostStatus.ARCHIVED)
        ))

        mockMvc.perform(get("/api/posts/search").param("q", "phone").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    @Test
    fun `active posts appear in search results`() {
        postRepository.saveAll(listOf(
            buildPost("Active Laptop", PostStatus.ACTIVE),
            buildPost("Archived Phone", PostStatus.ARCHIVED)
        ))

        mockMvc.perform(get("/api/posts/search").param("q", "laptop").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Active Laptop"))
    }

    @Test
    fun `archived posts do not appear in available posts`() {
        postRepository.saveAll(listOf(
            buildPost("Active Desk", PostStatus.ACTIVE),
            buildPost("Archived Chair", PostStatus.ARCHIVED)
        ))

        mockMvc.perform(get("/api/posts/available").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[?(@.title == 'Archived Chair')]").doesNotExist())
    }

    // ── POST /api/posts/bot/renew/{postId} ─────────────────────────────────────

    @Test
    fun `renew endpoint rejects wrong api key`() {
        val saved = postRepository.save(buildPost("Old Textbook", PostStatus.ARCHIVED))

        mockMvc.perform(
            post("/api/posts/bot/renew/${saved.postId}")
                .header("X-Bot-Api-Key", "wrong-key")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `renew endpoint reactivates an archived post`() {
        val saved = postRepository.save(buildPost("Expired Hoodie", PostStatus.ARCHIVED))

        mockMvc.perform(
            post("/api/posts/bot/renew/${saved.postId}")
                .header("X-Bot-Api-Key", "test-secret-key")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
    }

    @Test
    fun `renew endpoint resets expiresAt to 2 days from now`() {
        val saved = postRepository.save(buildPost("Expired Jacket", PostStatus.ARCHIVED))
        val expectedExpiry = LocalDateTime.now().plusDays(2).minusMinutes(1)

        mockMvc.perform(
            post("/api/posts/bot/renew/${saved.postId}")
                .header("X-Bot-Api-Key", "test-secret-key")
        ).andExpect(status().isOk)

        val renewed = postRepository.findById(saved.postId!!).get()
        assertTrue(renewed.expiresAt!!.isAfter(expectedExpiry))
    }

    @Test
    fun `renew endpoint clears reminderSentAt`() {
        val saved = postRepository.save(
            buildPost("Expired Bike", PostStatus.ARCHIVED, reminderSentAt = LocalDateTime.now().minusHours(2))
        )

        mockMvc.perform(
            post("/api/posts/bot/renew/${saved.postId}")
                .header("X-Bot-Api-Key", "test-secret-key")
        ).andExpect(status().isOk)

        val renewed = postRepository.findById(saved.postId!!).get()
        assertNull(renewed.reminderSentAt)
    }

    @Test
    fun `renewed post appears in search results again`() {
        val saved = postRepository.save(buildPost("Renewed Calculator", PostStatus.ARCHIVED))

        mockMvc.perform(
            post("/api/posts/bot/renew/${saved.postId}")
                .header("X-Bot-Api-Key", "test-secret-key")
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/posts/search").param("q", "calculator").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].title").value("Renewed Calculator"))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun buildPost(
        title: String,
        status: PostStatus = PostStatus.ACTIVE,
        reminderSentAt: LocalDateTime? = null
    ): Post = Post(
        title = title,
        price = BigDecimal("25.00"),
        description = "A test listing for expiry lifecycle tests",
        category = Category.CLOTHING,
        expiresAt = if (status == PostStatus.ARCHIVED) LocalDateTime.now().minusHours(1)
                    else LocalDateTime.now().plusDays(2),
        status = status,
        reminderSentAt = reminderSentAt,
        seller = seller
    )
}
