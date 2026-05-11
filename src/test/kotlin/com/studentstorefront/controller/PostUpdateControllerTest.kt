package com.studentstorefront.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.studentstorefront.entity.Post
import com.studentstorefront.entity.PostMedia
import com.studentstorefront.entity.Seller
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.PostMediaRepository
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.SellerRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@Transactional
@TestPropertySource(properties = [
    "whatsapp.bot.api-key=test-secret-key",
    "whatsapp.bot.phone=15551234567"
])
class PostUpdateControllerTest {

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
    @Autowired lateinit var postMediaRepository: PostMediaRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private lateinit var mockMvc: MockMvc
    private lateinit var owner: Seller
    private lateinit var otherSeller: Seller
    private lateinit var listing: Post

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        owner = sellerRepository.save(testSeller("Listing Owner", "listing-owner@constructor.university", "+15550000101"))
        otherSeller = sellerRepository.save(testSeller("Other Seller", "other-seller@constructor.university", "+15550000102"))

        listing = postRepository.save(
            Post(
                title = "Desk Chair",
                price = BigDecimal("35.00"),
                description = "Original listing description",
                category = Category.FURNITURE,
                seller = owner
            )
        )
        postMediaRepository.save(
            PostMedia(
                post = listing,
                mediaUrl = "https://cdn.example.com/original-chair.jpg",
                displayOrder = 0,
                isCover = true
            )
        )
    }

    @Test
    @WithMockUser(username = "listing-owner@constructor.university", roles = ["SELLER"])
    fun `owner can update a listing after posting it`() {
        mockMvc.perform(
            multipart("/api/posts/${listing.postId}/upload")
                .file(
                    MockMultipartFile(
                        "post",
                        "post.json",
                        MediaType.APPLICATION_JSON_VALUE,
                        body(
                            "title" to "Desk Chair Updated",
                            "price" to BigDecimal("42.50"),
                            "description" to "Updated listing description with fresher details",
                            "category" to "FURNITURE",
                            "imageOrder" to listOf(
                                mapOf("kind" to "existing", "url" to "https://cdn.example.com/original-chair.jpg")
                            )
                        ).toByteArray()
                    )
                )
                .param("coverIndex", "0")
                .with { request ->
                    request.method = "PUT"
                    request
                }
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Desk Chair Updated"))
            .andExpect(jsonPath("$.price").value(42.50))
            .andExpect(jsonPath("$.mediaUrls[0]").value("https://cdn.example.com/original-chair.jpg"))
    }

    @Test
    @WithMockUser(username = "other-seller@constructor.university", roles = ["SELLER"])
    fun `non owner cannot update another seller listing`() {
        mockMvc.perform(
            multipart("/api/posts/${listing.postId}/upload")
                .file(
                    MockMultipartFile(
                        "post",
                        "post.json",
                        MediaType.APPLICATION_JSON_VALUE,
                        body(
                            "title" to "Unauthorized change",
                            "description" to "Trying to edit someone else's listing",
                            "imageOrder" to listOf(
                                mapOf("kind" to "existing", "url" to "https://cdn.example.com/original-chair.jpg")
                            )
                        ).toByteArray()
                    )
                )
                .param("coverIndex", "0")
                .with { request ->
                    request.method = "PUT"
                    request
                }
        )
            .andExpect(status().isForbidden)
    }

    private fun testSeller(name: String, email: String, phone: String): Seller {
        return Seller(
            name = name,
            email = email,
            phoneNumber = phone,
            password = passwordEncoder.encode("Password1!") ?: "",
            role = Role.SELLER,
            isEnabled = true
        )
    }

    private fun body(vararg pairs: Pair<String, Any?>): String {
        return objectMapper.writeValueAsString(mapOf(*pairs))
    }
}
