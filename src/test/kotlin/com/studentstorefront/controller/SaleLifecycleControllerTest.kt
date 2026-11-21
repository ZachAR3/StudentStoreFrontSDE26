package com.studentstorefront.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.studentstorefront.entity.Post
import com.studentstorefront.entity.User
import com.studentstorefront.enums.Category
import com.studentstorefront.enums.PostStatus
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.PostRepository
import com.studentstorefront.repository.SaleRepository
import com.studentstorefront.repository.UserRepository
import com.studentstorefront.service.SaleService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
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
import java.time.ZoneOffset

/** Sale undo/decline/auto-revert (BE-02), web renewal (X-02) and the error envelope (BE-05). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@Transactional
@TestPropertySource(properties = [
    "whatsapp.bot.api-key=test-secret-key",
    "whatsapp.bot.phone=15551234567",
    "whatsapp.bot.api-url=http://localhost:3999",
    "app.base-url=http://localhost:8080"
])
class SaleLifecycleControllerTest {

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
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var saleRepository: SaleRepository
    @Autowired lateinit var saleService: SaleService
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private lateinit var mockMvc: MockMvc
    private lateinit var seller: User
    private lateinit var buyer: User
    private lateinit var listing: Post

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        seller = userRepository.save(testUser("Sale Seller", "sale-seller@constructor.university", "+15550001001"))
        buyer = userRepository.save(testUser("Sale Buyer", "sale-buyer@constructor.university", "+15550001002"))
        userRepository.save(testUser("Sale Other", "sale-other@constructor.university", "+15550001003"))
        listing = postRepository.save(
            Post(
                title = "Lifecycle Lamp",
                price = BigDecimal("15.00"),
                description = "A lamp used for sale lifecycle tests",
                category = Category.FURNITURE,
                user = seller
            )
        )
    }

    @Test
    @WithMockUser(username = "sale-seller@constructor.university", roles = ["USER"])
    fun `seller can undo an unconfirmed sale and the listing goes back on the market`() {
        markSold()
        mockMvc.perform(get("/api/posts/${listing.postId}"))
            .andExpect(jsonPath("$.awaitingBuyerConfirmation").value(true))

        mockMvc.perform(delete("/api/sales/posts/${listing.postId}"))
            .andExpect(status().isNoContent)

        assertReverted()
    }

    @Test
    @WithMockUser(username = "sale-seller@constructor.university", roles = ["USER"])
    fun `selling twice is a conflict with the shared error envelope`() {
        markSold()
        mockMvc.perform(
            patch("/api/posts/${listing.postId}/mark-sold")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("buyerUserId" to buyer.userId))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
            .andExpect(jsonPath("$.message").isString)
    }

    @Test
    @WithMockUser(username = "sale-seller@constructor.university", roles = ["USER"])
    fun `seller cannot undo a sale the buyer confirmed`() {
        markSold()
        val sale = saleRepository.findByPostPostId(listing.postId!!)!!
        sale.buyerConfirmedAt = LocalDateTime.now(ZoneOffset.UTC)
        saleRepository.saveAndFlush(sale)

        mockMvc.perform(delete("/api/sales/posts/${listing.postId}"))
            .andExpect(status().isConflict)
    }

    @Test
    fun `selected buyer can decline but nobody else can`() {
        markSold()

        mockMvc.perform(post("/api/sales/posts/${listing.postId}/decline").with(user("sale-other@constructor.university").roles("USER")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        mockMvc.perform(post("/api/sales/posts/${listing.postId}/decline").with(user("sale-buyer@constructor.university").roles("USER")))
            .andExpect(status().isNoContent)

        assertReverted()
    }

    @Test
    @WithMockUser(username = "sale-seller@constructor.university", roles = ["USER"])
    fun `unconfirmed sales older than the TTL are reverted`() {
        markSold()
        val soldAt = saleRepository.findByPostPostId(listing.postId!!)!!.soldAt

        assertEquals(0, saleService.revertUnconfirmedSales(soldAt.plus(SaleService.UNCONFIRMED_SALE_TTL).minusMinutes(1)))
        assertEquals(1, saleService.revertUnconfirmedSales(soldAt.plus(SaleService.UNCONFIRMED_SALE_TTL).plusMinutes(1)))

        assertReverted()
    }

    @Test
    @WithMockUser(username = "sale-seller@constructor.university", roles = ["USER"])
    fun `owner can renew an archived listing from the web`() {
        postRepository.saveAndFlush(listing.copy(status = PostStatus.ARCHIVED, expiresAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(1)))

        mockMvc.perform(post("/api/posts/${listing.postId}/renew"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACTIVE"))

        val renewed = postRepository.findById(listing.postId!!).get()
        assertEquals(PostStatus.ACTIVE, renewed.status)
        assertTrue(renewed.expiresAt!!.isAfter(LocalDateTime.now(ZoneOffset.UTC).plusDays(1)))
    }

    @Test
    @WithMockUser(username = "sale-other@constructor.university", roles = ["USER"])
    fun `non-owner cannot renew a listing`() {
        postRepository.saveAndFlush(listing.copy(status = PostStatus.ARCHIVED))

        mockMvc.perform(post("/api/posts/${listing.postId}/renew"))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = "sale-seller@constructor.university", roles = ["USER"])
    fun `validation errors list the offending fields`() {
        mockMvc.perform(
            post("/api/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("saleId" to 1, "rating" to 9))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.rating").isString)
    }

    private fun markSold() {
        mockMvc.perform(
            patch("/api/posts/${listing.postId}/mark-sold")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("buyerUserId" to buyer.userId))
                .with(user("sale-seller@constructor.university").roles("USER"))
        ).andExpect(status().isOk)
    }

    private fun assertReverted() {
        assertNull(saleRepository.findByPostPostId(listing.postId!!))
        val post = postRepository.findById(listing.postId!!).get()
        assertFalse(post.isSold)
        assertNull(post.buyer)
        assertEquals(PostStatus.ACTIVE, post.status)
        assertTrue(post.expiresAt!!.isAfter(LocalDateTime.now(ZoneOffset.UTC)))
    }

    private fun testUser(name: String, email: String, phone: String) = User(
        name = name,
        email = email,
        phoneNumber = phone,
        password = passwordEncoder.encode("Password1!") ?: "",
        role = Role.USER,
        isEnabled = true
    )

    private fun body(vararg pairs: Pair<String, Any?>): String = objectMapper.writeValueAsString(mapOf(*pairs))
}
