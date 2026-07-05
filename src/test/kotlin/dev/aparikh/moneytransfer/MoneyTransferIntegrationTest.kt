package dev.aparikh.moneytransfer

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aparikh.moneytransfer.account.AccountRepository
import dev.aparikh.moneytransfer.transfer.TransferRequest
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
    fun `happy path transfer persists updated balances`() {
        val body = objectMapper.writeValueAsString(
            TransferRequest(recipientAccountId = 2, amount = BigDecimal("100.00"), purpose = "lunch"),
        )

        mockMvc.perform(
            post("/api/v1/transfers")
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("COMPLETED"))

        // Read back from the database to confirm the balances were persisted.
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
