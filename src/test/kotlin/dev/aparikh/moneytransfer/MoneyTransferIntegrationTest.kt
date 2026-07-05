package dev.aparikh.moneytransfer

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aparikh.moneytransfer.account.AccountRepository
import dev.aparikh.moneytransfer.transfer.TransferRequest
import dev.aparikh.moneytransfer.transfer.TransferService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal

/**
 * End-to-end tests against a real PostgreSQL (Testcontainers) with Flyway-managed schema + seed.
 * Each test runs in a transaction that rolls back, so it starts from the seed balances.
 * Skipped automatically where Docker is unavailable.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@Testcontainers(disabledWithoutDocker = true)
class MoneyTransferIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var accounts: AccountRepository

    @Autowired
    lateinit var transferService: TransferService

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    }

    @Test
    fun contextLoads() {
        // Verifies the application context boots and Flyway migrations apply on a clean database.
    }

    @Test
    fun `happy path transfer reserves funds as PENDING, then settlement credits the recipient`() {
        val body = objectMapper.writeValueAsString(
            TransferRequest(recipientAccountId = 2, amount = BigDecimal("100.00"), purpose = "lunch"),
        )

        // Step 7 contract change: create no longer settles — it debits the sender and queues
        // the transfer as PENDING (funds reservation); the recipient is credited at settlement.
        val response = mockMvc.perform(
            post("/api/v1/transfers")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.settleAt").exists())
            .andReturn()

        assertEquals(0, accounts.findById(1).get().balance.compareTo(BigDecimal("900.00")), "sender debited immediately")
        assertEquals(0, accounts.findById(2).get().balance.compareTo(BigDecimal("500.00")), "recipient untouched while PENDING")

        // Drive settlement directly (the scheduler owns *when*; settle owns *what happens*).
        val transferId = objectMapper.readTree(response.response.contentAsString)["id"].asLong()
        transferService.settle(transferId)

        assertEquals(0, accounts.findById(2).get().balance.compareTo(BigDecimal("600.00")), "recipient credited at settlement")
    }

    @Test
    fun `cancelling a pending transfer refunds the sender - cancelling again is 409`() {
        val body = objectMapper.writeValueAsString(
            TransferRequest(recipientAccountId = 2, amount = BigDecimal("100.00"), purpose = "oops"),
        )
        val response = mockMvc.perform(
            post("/api/v1/transfers").header("X-User-Id", "1").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn()
        val transferId = objectMapper.readTree(response.response.contentAsString)["id"].asLong()

        mockMvc.perform(post("/api/v1/transfers/$transferId/cancel").header("X-User-Id", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        assertEquals(0, accounts.findById(1).get().balance.compareTo(BigDecimal("1000.00")), "sender refunded")
        assertEquals(0, accounts.findById(2).get().balance.compareTo(BigDecimal("500.00")), "recipient never touched")

        mockMvc.perform(post("/api/v1/transfers/$transferId/cancel").header("X-User-Id", "1"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.title").value("Transfer not cancellable"))
    }

    @Test
    fun `a settled transfer cannot be cancelled`() {
        val body = objectMapper.writeValueAsString(
            TransferRequest(recipientAccountId = 2, amount = BigDecimal("100.00"), purpose = "final"),
        )
        val response = mockMvc.perform(
            post("/api/v1/transfers").header("X-User-Id", "1").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn()
        val transferId = objectMapper.readTree(response.response.contentAsString)["id"].asLong()

        transferService.settle(transferId)

        mockMvc.perform(post("/api/v1/transfers/$transferId/cancel").header("X-User-Id", "1"))
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Transfer not cancellable"))

        // Settlement stands: no refund happened.
        assertEquals(0, accounts.findById(1).get().balance.compareTo(BigDecimal("900.00")))
        assertEquals(0, accounts.findById(2).get().balance.compareTo(BigDecimal("600.00")))
    }

    @Test
    fun `transfer exceeding balance returns 422 problem detail and leaves balance unchanged`() {
        val body = objectMapper.writeValueAsString(
            TransferRequest(recipientAccountId = 2, amount = BigDecimal("2000.00"), purpose = "too much"),
        )

        mockMvc.perform(
            post("/api/v1/transfers")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Insufficient funds"))

        assertEquals(0, accounts.findById(1).get().balance.compareTo(BigDecimal("1000.00")))
    }

    @Test
    fun `transfer to an unknown account returns 404`() {
        val body = objectMapper.writeValueAsString(
            TransferRequest(recipientAccountId = 999, amount = BigDecimal("10.00"), purpose = null),
        )

        mockMvc.perform(
            post("/api/v1/transfers")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Account not found"))
    }

    @Test
    fun `list contacts returns all, and an ambiguous name returns two Daniels`() {
        mockMvc.perform(get("/api/v1/contacts").header("X-User-Id", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(5))

        mockMvc.perform(get("/api/v1/contacts").header("X-User-Id", "1").param("name", "Daniel"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `openapi docs and swagger ui are served`() {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
    }
}
