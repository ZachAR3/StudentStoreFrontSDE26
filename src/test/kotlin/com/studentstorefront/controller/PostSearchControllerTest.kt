package com.studentstorefront.controller

import com.studentstorefront.entity.Post
import com.studentstorefront.entity.User
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
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
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:15.13")

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var webApplicationContext: WebApplicationContext
    @Autowired lateinit var sellerRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private lateinit var mockMvc: MockMvc
    private lateinit var user: User
    private lateinit var posts: List<Post>

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        user = sellerRepository.save(
            User(
                name = "Search User",
                email = "search@constructor.university",
                phoneNumber = "15550001111",
                password = passwordEncoder.encode("password") ?: "",
                role = Role.USER
            )
        )

        posts = postRepository.saveAll(
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
    fun `anonymous search responses hide seller contact details`() {
        mockMvc.perform(
            get("/api/posts/search")
                .param("q", "standing")
                .param("size", "10")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].whatsappPhone").doesNotExist())
            .andExpect(jsonPath("$.content[0].email").doesNotExist())
    }

    @Test
    @WithMockUser(username = "viewer@constructor.university")
    fun `signed-in search responses include seller contact details`() {
        sellerRepository.save(
            User(
                name = "Viewer",
                email = "viewer@constructor.university",
                phoneNumber = "15550002222",
                password = passwordEncoder.encode("password") ?: "",
                role = Role.USER
            )
        )

        mockMvc.perform(
            get("/api/posts/search")
                .param("q", "standing")
                .param("size", "10")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].whatsappPhone").value("15550001111"))
            .andExpect(jsonPath("$.content[0].email").value("search@constructor.university"))
    }

    @Test
    fun `bot contact endpoint rejects a wrong api key`() {
        mockMvc.perform(
            get("/api/posts/bot/${posts[0].postId}/contact")
                .header("X-Bot-Api-Key", "wrong-key")
                .header("X-Bot-Phone", "15550003333")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `bot contact endpoint refuses unregistered requesters`() {
        mockMvc.perform(
            get("/api/posts/bot/${posts[0].postId}/contact")
                .header("X-Bot-Api-Key", "test-secret-key")
                .header("X-Bot-Phone", "15550009999")
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `bot contact endpoint returns seller phone to verified members`() {
        saveVerifiedRequester()

        mockMvc.perform(
            get("/api/posts/bot/${posts[0].postId}/contact")
                .header("X-Bot-Api-Key", "test-secret-key")
                .header("X-Bot-Phone", "15550003333")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.postId").value(posts[0].postId!!))
            .andExpect(jsonPath("$.sellerName").value("Search User"))
            .andExpect(jsonPath("$.whatsappPhone").value("15550001111"))
    }

    @Test
    fun `bot contact endpoint hides sold listings`() {
        saveVerifiedRequester()
        val sold = posts.first { it.isSold }

        mockMvc.perform(
            get("/api/posts/bot/${sold.postId}/contact")
                .header("X-Bot-Api-Key", "test-secret-key")
                .header("X-Bot-Phone", "15550003333")
        ).andExpect(status().isNotFound)
    }

    private fun saveVerifiedRequester(): User = sellerRepository.save(
        User(
            name = "Bot Buyer",
            email = "bot-buyer@constructor.university",
            phoneNumber = "+15550003333",
            password = passwordEncoder.encode("password") ?: "",
            role = Role.USER,
            isEnabled = true
        ).apply { phoneVerifiedAt = LocalDateTime.now() }
    )

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
            status = if (isSold) PostStatus.SOLD else PostStatus.ACTIVE,
            createdAt = createdAt,
            user = user
        )
    }
}
