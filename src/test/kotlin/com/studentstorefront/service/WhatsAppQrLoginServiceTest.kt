package com.studentstorefront.service

import com.studentstorefront.entity.User
import com.studentstorefront.entity.WhatsAppLoginSession
import com.studentstorefront.enums.Role
import com.studentstorefront.enums.WhatsAppSessionStatus
import com.studentstorefront.repository.UserRepository
import com.studentstorefront.repository.WhatsAppLoginSessionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.User as SecurityUser
import java.time.LocalDateTime
import java.util.*

class WhatsAppQrLoginServiceTest {

    private val sessionRepository: WhatsAppLoginSessionRepository = mockk()
    private val sellerRepository: UserRepository = mockk()
    private val jwtService: JwtService = mockk()
    private val userDetailsService: UserDetailsService = mockk()

    private val service = WhatsAppQrLoginService(
        sessionRepository = sessionRepository,
        sellerRepository = sellerRepository,
        jwtService = jwtService,
        userDetailsService = userDetailsService,
        botPhone = "15551234567"
    )

    @BeforeEach
    fun setUp() {
        every { sessionRepository.save(any()) } returnsArgument 0
    }

    // ── createSession ──────────────────────────────────────────────────────────

    @Test
    fun `createSession saves session and builds correct wa_me URL`() {
        val slot = slot<WhatsAppLoginSession>()
        every { sessionRepository.save(capture(slot)) } returnsArgument 0

        val result = service.createSession("127.0.0.1")

        verify(exactly = 1) { sessionRepository.save(any()) }
        assertEquals(slot.captured.sessionId.toString(), result.sessionId)
        assertTrue(result.qrContent.startsWith("https://wa.me/15551234567?text=login%3A"))
        assertTrue(result.qrContent.contains(slot.captured.loginToken.toString()))
    }

    @Test
    fun `createSession sets expiresAt 5 minutes in the future`() {
        val before = LocalDateTime.now().plusMinutes(4).plusSeconds(50)
        val after  = LocalDateTime.now().plusMinutes(5).plusSeconds(10)
        val slot = slot<WhatsAppLoginSession>()
        every { sessionRepository.save(capture(slot)) } returnsArgument 0

        service.createSession("127.0.0.1")

        assertTrue(slot.captured.expiresAt.isAfter(before))
        assertTrue(slot.captured.expiresAt.isBefore(after))
    }

    // ── pollSession ────────────────────────────────────────────────────────────

    @Test
    fun `pollSession returns NOT_FOUND for unknown sessionId`() {
        every { sessionRepository.findBySessionId(any()) } returns null

        val result = service.pollSession(UUID.randomUUID())

        assertEquals("NOT_FOUND", result.status)
        assertNull(result.claimToken)
    }

    @Test
    fun `pollSession returns PENDING with null claimToken`() {
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1")
        every { sessionRepository.findBySessionId(session.sessionId) } returns session

        val result = service.pollSession(session.sessionId)

        assertEquals("PENDING", result.status)
        assertNull(result.claimToken)
    }

    @Test
    fun `pollSession returns COMPLETED with claimToken`() {
        val claimToken = UUID.randomUUID()
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1").apply {
            status = WhatsAppSessionStatus.COMPLETED
            this.claimToken = claimToken
            completedAt = LocalDateTime.now()
        }
        every { sessionRepository.findBySessionId(session.sessionId) } returns session

        val result = service.pollSession(session.sessionId)

        assertEquals("COMPLETED", result.status)
        assertEquals(claimToken.toString(), result.claimToken)
    }

    @Test
    fun `pollSession returns EXPIRED with null claimToken`() {
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1").apply {
            status = WhatsAppSessionStatus.EXPIRED
        }
        every { sessionRepository.findBySessionId(session.sessionId) } returns session

        val result = service.pollSession(session.sessionId)

        assertEquals("EXPIRED", result.status)
        assertNull(result.claimToken)
    }

    @Test
    fun `pollSession returns PHONE_NOT_LINKED with null claimToken`() {
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1").apply {
            status = WhatsAppSessionStatus.PHONE_NOT_LINKED
        }
        every { sessionRepository.findBySessionId(session.sessionId) } returns session

        val result = service.pollSession(session.sessionId)

        assertEquals("PHONE_NOT_LINKED", result.status)
        assertNull(result.claimToken)
    }

    // ── confirmLogin ───────────────────────────────────────────────────────────

    @Test
    fun `confirmLogin returns NOT_FOUND for unknown loginToken`() {
        every { sessionRepository.findByLoginToken(any()) } returns null

        val result = service.confirmLogin(UUID.randomUUID(), "15559999999")

        assertEquals("NOT_FOUND", result.result)
    }

    @Test
    fun `confirmLogin returns EXPIRED and saves session when TTL has elapsed`() {
        val session = WhatsAppLoginSession(
            creatorIp = "127.0.0.1",
            expiresAt = LocalDateTime.now().minusSeconds(1)
        )
        every { sessionRepository.findByLoginToken(session.loginToken) } returns session

        val result = service.confirmLogin(session.loginToken, "15559999999")

        assertEquals("EXPIRED", result.result)
        assertEquals(WhatsAppSessionStatus.EXPIRED, session.status)
        verify { sessionRepository.save(session) }
    }

    @Test
    fun `confirmLogin returns EXPIRED when status is already EXPIRED`() {
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1").apply {
            status = WhatsAppSessionStatus.EXPIRED
        }
        every { sessionRepository.findByLoginToken(session.loginToken) } returns session

        val result = service.confirmLogin(session.loginToken, "15559999999")

        assertEquals("EXPIRED", result.result)
    }

    @Test
    fun `confirmLogin returns ALREADY_USED when session is COMPLETED`() {
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1").apply {
            status = WhatsAppSessionStatus.COMPLETED
        }
        every { sessionRepository.findByLoginToken(session.loginToken) } returns session

        val result = service.confirmLogin(session.loginToken, "15559999999")

        assertEquals("ALREADY_USED", result.result)
    }

    @Test
    fun `confirmLogin returns ALREADY_USED when session is CLAIMED`() {
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1").apply {
            status = WhatsAppSessionStatus.CLAIMED
        }
        every { sessionRepository.findByLoginToken(session.loginToken) } returns session

        val result = service.confirmLogin(session.loginToken, "15559999999")

        assertEquals("ALREADY_USED", result.result)
    }

    @Test
    fun `confirmLogin returns PHONE_NOT_LINKED and saves session when no user matches phone`() {
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1")
        every { sessionRepository.findByLoginToken(session.loginToken) } returns session
        every { sellerRepository.findByPhoneNumber("+99999999999") } returns null

        val result = service.confirmLogin(session.loginToken, "99999999999")

        assertEquals("PHONE_NOT_LINKED", result.result)
        assertEquals(WhatsAppSessionStatus.PHONE_NOT_LINKED, session.status)
        assertEquals("+99999999999", session.phoneNumber)
        verify { sessionRepository.save(session) }
    }

    @Test
    fun `confirmLogin returns PHONE_NOT_LINKED when phone belongs to unverified user`() {
        val user = buildSeller(isEnabled = false)
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1")
        every { sessionRepository.findByLoginToken(session.loginToken) } returns session
        every { sellerRepository.findByPhoneNumber("+${user.phoneNumber}") } returns user

        val result = service.confirmLogin(session.loginToken, user.phoneNumber)

        assertEquals("PHONE_NOT_LINKED", result.result)
        assertEquals(WhatsAppSessionStatus.PHONE_NOT_LINKED, session.status)
        assertEquals("+${user.phoneNumber}", session.phoneNumber)
        verify { sessionRepository.save(session) }
    }

    @Test
    fun `confirmLogin returns OK and transitions session to COMPLETED with claimToken`() {
        val user = buildSeller()
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1")
        every { sessionRepository.findByLoginToken(session.loginToken) } returns session
        every { sellerRepository.findByPhoneNumber("+${user.phoneNumber}") } returns user

        val result = service.confirmLogin(session.loginToken, user.phoneNumber)

        assertEquals("OK", result.result)
        assertEquals(WhatsAppSessionStatus.COMPLETED, session.status)
        assertEquals(user.userId, session.userId)
        assertEquals("+${user.phoneNumber}", session.phoneNumber)
        assertNotNull(session.claimToken)
        assertNotNull(session.completedAt)
        verify { sessionRepository.save(session) }
    }

    // ── claimJwt ───────────────────────────────────────────────────────────────

    @Test
    fun `claimJwt returns null for unknown claimToken`() {
        every { sessionRepository.findByClaimToken(any()) } returns null

        assertNull(service.claimJwt(UUID.randomUUID()))
    }

    @Test
    fun `claimJwt returns null when session status is not COMPLETED`() {
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1").apply {
            claimToken = UUID.randomUUID()
            status = WhatsAppSessionStatus.CLAIMED
            completedAt = LocalDateTime.now().minusSeconds(10)
        }
        every { sessionRepository.findByClaimToken(session.claimToken!!) } returns session

        assertNull(service.claimJwt(session.claimToken!!))
    }

    @Test
    fun `claimJwt returns null when claimToken is past its 5-minute TTL`() {
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1").apply {
            status = WhatsAppSessionStatus.COMPLETED
            claimToken = UUID.randomUUID()
            completedAt = LocalDateTime.now().minusMinutes(6)
        }
        every { sessionRepository.findByClaimToken(session.claimToken!!) } returns session

        assertNull(service.claimJwt(session.claimToken!!))
    }

    @Test
    fun `claimJwt returns null and downgrades stale session when user is unverified`() {
        val user = buildSeller(isEnabled = false)
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1").apply {
            status = WhatsAppSessionStatus.COMPLETED
            claimToken = UUID.randomUUID()
            userId = user.userId
            completedAt = LocalDateTime.now().minusSeconds(30)
        }
        every { sessionRepository.findByClaimToken(session.claimToken!!) } returns session
        every { sellerRepository.findById(user.userId!!) } returns Optional.of(user)

        val result = service.claimJwt(session.claimToken!!)

        assertNull(result)
        assertEquals(WhatsAppSessionStatus.PHONE_NOT_LINKED, session.status)
        assertNull(session.claimToken)
        verify { sessionRepository.save(session) }
    }

    @Test
    fun `claimJwt issues JWT and transitions session to CLAIMED`() {
        val user = buildSeller()
        val userDetails = SecurityUser(user.email, user.password, emptyList())
        val session = WhatsAppLoginSession(creatorIp = "127.0.0.1").apply {
            status = WhatsAppSessionStatus.COMPLETED
            claimToken = UUID.randomUUID()
            userId = user.userId
            completedAt = LocalDateTime.now().minusSeconds(30)
        }
        every { sessionRepository.findByClaimToken(session.claimToken!!) } returns session
        every { sellerRepository.findById(user.userId!!) } returns Optional.of(user)
        every { userDetailsService.loadUserByUsername(user.email) } returns userDetails
        every { jwtService.generateToken(userDetails) } returns "test.jwt.token"

        val result = service.claimJwt(session.claimToken!!)

        assertNotNull(result)
        assertEquals("test.jwt.token", result!!.token)
        assertEquals("Bearer", result.type)
        assertEquals(user.email, result.user.email)
        assertEquals(user.userId, result.user.userId)
        assertEquals(WhatsAppSessionStatus.CLAIMED, session.status)
        assertNotNull(session.claimedAt)
        verify { sessionRepository.save(session) }
    }

    @Test
    fun `expireStaleSessions delegates to repository with current time`() {
        every { sessionRepository.expireStaleSessions(any()) } returns 3

        service.expireStaleSessions()

        verify(exactly = 1) { sessionRepository.expireStaleSessions(any()) }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun buildSeller(isEnabled: Boolean = true) = User(
        userId = 1L,
        name = "Test User",
        email = "test@constructor.university",
        phoneNumber = "15551234567",
        password = "hashed",
        role = Role.USER,
        isEnabled = isEnabled
    )
}
