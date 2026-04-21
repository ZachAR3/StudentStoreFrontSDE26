package com.studentstorefront.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.studentstorefront.entity.Seller
import com.studentstorefront.enums.Role
import com.studentstorefront.repository.SellerRepository
import com.studentstorefront.repository.WhatsAppLoginSessionRepository
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@Transactional
@TestPropertySource(properties = [
    "whatsapp.bot.api-key=test-secret-key",
    "whatsapp.bot.phone=15551234567"
])
class WhatsAppQrLoginControllerTest {

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
    @Autowired lateinit var sessionRepository: WhatsAppLoginSessionRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private val objectMapper = ObjectMapper().registerKotlinModule()

    private lateinit var mockMvc: MockMvc
    private lateinit var testSeller: Seller

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        testSeller = sellerRepository.save(
            Seller(
                name = "QR Test Seller",
                email = "qrtest@constructor.university",
                phoneNumber = "15559876543",
                password = passwordEncoder.encode("password") ?: "",
                role = Role.SELLER
            )
        )
    }

    // ── POST /session ──────────────────────────────────────────────────────────

    @Test
    fun `POST session returns 201 with sessionId, qrContent pointing to bot, and expiresAt`() {
        mockMvc.perform(post("/api/auth/whatsapp/session"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.sessionId").isString)
            .andExpect(jsonPath("$.qrContent").value(containsString("wa.me/15551234567")))
            .andExpect(jsonPath("$.qrContent").value(containsString("login%3A")))
            .andExpect(jsonPath("$.expiresAt").isString)
    }

    // ── GET /session/{sessionId} ───────────────────────────────────────────────

    @Test
    fun `GET session returns PENDING right after creation`() {
        val session = createSession()

        mockMvc.perform(get("/api/auth/whatsapp/session/${session.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    @Test
    fun `GET session returns 400 for a malformed sessionId`() {
        mockMvc.perform(get("/api/auth/whatsapp/session/not-a-uuid"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET session returns NOT_FOUND status for a random valid UUID`() {
        mockMvc.perform(get("/api/auth/whatsapp/session/00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("NOT_FOUND"))
    }

    // ── POST /confirm ──────────────────────────────────────────────────────────

    @Test
    fun `POST confirm returns 401 when X-Bot-Api-Key is wrong`() {
        val session = createSession()

        mockMvc.perform(
            post("/api/auth/whatsapp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Bot-Api-Key", "wrong-key")
                .content(body("loginToken" to session.loginToken, "phoneNumber" to testSeller.phoneNumber))
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST confirm returns 400 for a malformed loginToken`() {
        mockMvc.perform(
            post("/api/auth/whatsapp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Bot-Api-Key", "test-secret-key")
                .content(body("loginToken" to "not-a-uuid", "phoneNumber" to testSeller.phoneNumber))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST confirm returns NOT_FOUND for an unknown loginToken`() {
        mockMvc.perform(
            post("/api/auth/whatsapp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Bot-Api-Key", "test-secret-key")
                .content(body("loginToken" to "00000000-0000-0000-0000-000000000000", "phoneNumber" to testSeller.phoneNumber))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("NOT_FOUND"))
    }

    @Test
    fun `POST confirm returns PHONE_NOT_LINKED when phone has no registered seller`() {
        val session = createSession()

        botConfirm(session.loginToken, "99999999999")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("PHONE_NOT_LINKED"))
    }

    @Test
    fun `POST confirm returns ALREADY_USED on a second confirmation attempt`() {
        val session = createSession()
        botConfirm(session.loginToken, testSeller.phoneNumber)

        botConfirm(session.loginToken, testSeller.phoneNumber)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("ALREADY_USED"))
    }

    // ── POST /claim ────────────────────────────────────────────────────────────

    @Test
    fun `POST claim returns 400 for a malformed claimToken`() {
        mockMvc.perform(
            post("/api/auth/whatsapp/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("claimToken" to "not-a-uuid"))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST claim returns 410 Gone for an unknown claimToken`() {
        mockMvc.perform(
            post("/api/auth/whatsapp/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("claimToken" to "00000000-0000-0000-0000-000000000000"))
        ).andExpect(status().isGone)
    }

    // ── Full happy path ────────────────────────────────────────────────────────

    @Test
    fun `full happy path - create session, bot confirms, poll COMPLETED, claim JWT`() {
        val session = createSession()

        botConfirm(session.loginToken, testSeller.phoneNumber)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("OK"))

        val claimToken = mockMvc.perform(get("/api/auth/whatsapp/session/${session.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.claimToken").isString)
            .andReturn()
            .let { objectMapper.readTree(it.response.contentAsString).get("claimToken").asText() }

        mockMvc.perform(
            post("/api/auth/whatsapp/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("claimToken" to claimToken))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").isString)
            .andExpect(jsonPath("$.type").value("Bearer"))
            .andExpect(jsonPath("$.seller.email").value(testSeller.email))
            .andExpect(jsonPath("$.seller.phoneNumber").value(testSeller.phoneNumber))
    }

    @Test
    fun `POST claim returns 410 Gone when the same claimToken is used twice`() {
        val session = createSession()
        botConfirm(session.loginToken, testSeller.phoneNumber)
        val claimToken = pollClaimToken(session.id)

        mockMvc.perform(
            post("/api/auth/whatsapp/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("claimToken" to claimToken))
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/auth/whatsapp/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("claimToken" to claimToken))
        ).andExpect(status().isGone)
    }

    @Test
    fun `poll returns CLAIMED status after the claimToken has been consumed`() {
        val session = createSession()
        botConfirm(session.loginToken, testSeller.phoneNumber)
        val claimToken = pollClaimToken(session.id)

        mockMvc.perform(
            post("/api/auth/whatsapp/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("claimToken" to claimToken))
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/auth/whatsapp/session/${session.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CLAIMED"))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private data class SessionData(val id: String, val loginToken: String)

    private fun createSession(): SessionData {
        val response = mockMvc.perform(post("/api/auth/whatsapp/session"))
            .andExpect(status().isCreated)
            .andReturn()
        val tree = objectMapper.readTree(response.response.contentAsString)
        return SessionData(
            id = tree.get("sessionId").asText(),
            loginToken = tree.get("qrContent").asText().substringAfter("login%3A")
        )
    }

    private fun botConfirm(loginToken: String, phoneNumber: String) =
        mockMvc.perform(
            post("/api/auth/whatsapp/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Bot-Api-Key", "test-secret-key")
                .content(body("loginToken" to loginToken, "phoneNumber" to phoneNumber))
        )

    private fun pollClaimToken(sessionId: String): String {
        val response = mockMvc.perform(get("/api/auth/whatsapp/session/$sessionId")).andReturn()
        return objectMapper.readTree(response.response.contentAsString).get("claimToken").asText()
    }

    private fun body(vararg pairs: Pair<String, String>): String =
        objectMapper.writeValueAsString(pairs.toMap())
}
