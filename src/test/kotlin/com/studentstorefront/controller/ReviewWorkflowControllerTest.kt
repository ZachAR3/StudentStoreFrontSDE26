package com.studentstorefront.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.studentstorefront.entity.Post
import com.studentstorefront.entity.Seller
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.ReviewRepository
import com.studentstorefront.repository.SellerRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
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
    "whatsapp.bot.api-url=http://localhost:3999",
    "app.base-url=http://localhost:8080"
])
class ReviewWorkflowControllerTest {

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
    @Autowired lateinit var reviewRepository: ReviewRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private lateinit var mockMvc: MockMvc
    private lateinit var seller: Seller
    private lateinit var buyer: Seller
    private lateinit var otherBuyer: Seller
    private lateinit var listing: Post

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        seller = sellerRepository.save(testSeller("Seller One", "seller-one@constructor.university", "+15550000001"))
        buyer = sellerRepository.save(testSeller("Buyer One", "buyer-one@constructor.university", "+15550000002"))
        otherBuyer = sellerRepository.save(testSeller("Other Buyer", "other-buyer@constructor.university", "+15550000003"))
        listing = postRepository.save(
            Post(
                title = "Review Desk",
                price = BigDecimal("20.00"),
                description = "A desk used for review workflow tests",
                category = Category.FURNITURE,
                seller = seller
            )
        )
    }

    @Test
    @WithMockUser(username = "seller-one@constructor.university", roles = ["SELLER"])
    fun `mark sold requires a registered buyer and stores sale details`() {
        mockMvc.perform(
            patch("/api/posts/${listing.postId}/mark-sold")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("buyerId" to buyer.sellerId))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sold").value(true))
            .andExpect(jsonPath("$.buyer.sellerId").value(buyer.sellerId))
            .andExpect(jsonPath("$.soldAt").isString)
    }

    @Test
    @WithMockUser(username = "seller-one@constructor.university", roles = ["SELLER"])
    fun `seller cannot select themselves as buyer`() {
        mockMvc.perform(
            patch("/api/posts/${listing.postId}/mark-sold")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("buyerId" to seller.sellerId))
        ).andExpect(status().isBadRequest)
    }

    @Test
    @WithMockUser(username = "buyer-one@constructor.university", roles = ["SELLER"])
    fun `buyer can review seller once after sale`() {
        val soldPost = saveSoldPost()

        mockMvc.perform(
            post("/api/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("postId" to soldPost.postId, "rating" to 5, "comment" to "Easy pickup"))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.direction").value("BUYER_TO_SELLER"))
            .andExpect(jsonPath("$.reviewee.sellerId").value(seller.sellerId))

        mockMvc.perform(
            post("/api/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("postId" to soldPost.postId, "rating" to 4))
        ).andExpect(status().isBadRequest)
    }

    @Test
    @WithMockUser(username = "seller-one@constructor.university", roles = ["SELLER"])
    fun `seller can review buyer after sale`() {
        val soldPost = saveSoldPost()

        mockMvc.perform(
            post("/api/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("postId" to soldPost.postId, "rating" to 4, "comment" to "On time"))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.direction").value("SELLER_TO_BUYER"))
            .andExpect(jsonPath("$.reviewee.sellerId").value(buyer.sellerId))
    }

    @Test
    @WithMockUser(username = "other-buyer@constructor.university", roles = ["SELLER"])
    fun `unrelated user cannot review a transaction`() {
        val soldPost = saveSoldPost()

        mockMvc.perform(
            post("/api/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("postId" to soldPost.postId, "rating" to 5))
        ).andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = "buyer-one@constructor.university", roles = ["SELLER"])
    fun `pending reviews returns buyer review request`() {
        saveSoldPost()

        mockMvc.perform(get("/api/reviews/pending"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].direction").value("BUYER_TO_SELLER"))
            .andExpect(jsonPath("$[0].reviewee.sellerId").value(seller.sellerId))
    }

    @Test
    @WithMockUser(username = "buyer-one@constructor.university", roles = ["SELLER"])
    fun `profile reviews aggregate seller ratings`() {
        val soldPost = saveSoldPost()
        reviewRepository.save(
            com.studentstorefront.entity.Review(
                post = soldPost,
                reviewer = buyer,
                reviewee = seller,
                direction = com.studentstorefront.enums.ReviewDirection.BUYER_TO_SELLER,
                rating = 5,
                comment = "Helpful seller"
            )
        )

        mockMvc.perform(get("/api/reviews/profile/${seller.sellerId}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sellerRating.average").value(5.0))
            .andExpect(jsonPath("$.sellerRating.count").value(1))
            .andExpect(jsonPath("$.recentReviews[0].comment").value("Helpful seller"))
    }

    private fun saveSoldPost(): Post {
        return postRepository.save(
            listing.copy(
                isSold = true,
                buyer = buyer,
                soldAt = LocalDateTime.now()
            )
        )
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
