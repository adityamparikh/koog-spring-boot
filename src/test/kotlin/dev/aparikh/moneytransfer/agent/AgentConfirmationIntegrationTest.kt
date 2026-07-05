package dev.aparikh.moneytransfer.agent

import dev.aparikh.moneytransfer.account.AccountRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.util.UUID

/**
 * Integration test for the confirm-gate money path against **real PostgreSQL** (Testcontainers +
 * Flyway seed): seed a staged transfer, affirm via `AgentService.reply`, and assert balances
 * actually moved in the database (AC-11 / AC-13 end-to-end, no live LLM — the deterministic
 * regex AffirmationInterpreter resolves "yes"/"no").
 *
 * Driven at the service layer (not through MockMvc) on purpose: a `suspend` controller completes
 * via async dispatch on a *separate* thread, so the test's `@Transactional` rollback would not
 * cover the handler's DB writes and tests would pollute each other. Calling `reply` under
 * `runBlocking` keeps the transfer on the test thread, inside the rolled-back transaction. The
 * controller wiring itself is covered by `AgentControllerTest` (`@WebMvcTest`).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Testcontainers(disabledWithoutDocker = true)
class AgentConfirmationIntegrationTest {

    @Autowired
    lateinit var agentService: AgentService

    @Autowired
    lateinit var accounts: AccountRepository

    @Autowired
    lateinit var pending: PendingInteractionStore

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    }

    /** Seeds a staged transfer as if `prepareTransfer` had run this turn. */
    private fun stage(conversationId: UUID, amount: String) = pending.put(
        conversationId,
        PendingInteraction.Confirmation(
            StagedTransfer(
                senderAccountId = 1, recipientAccountId = 2, recipientDisplay = "Alice Smith",
                amount = BigDecimal(amount), purpose = "lunch",
            ),
        ),
    )

    @Test
    fun `affirming a staged transfer moves money in Postgres`() {
        val conversationId = UUID.randomUUID()
        stage(conversationId, "100.00") // seed balances: account 1 = 1000, account 2 = 500

        val result = runBlocking { agentService.reply(accountId = 1, conversationId = conversationId, answer = "yes") }

        assertEquals(InteractionType.ANSWER, result.type)
        assertEquals(0, accounts.findById(1).get().balance.compareTo(BigDecimal("900.00")))
        assertEquals(0, accounts.findById(2).get().balance.compareTo(BigDecimal("600.00")))
        assertNull(pending.get(conversationId)) // pending cleared after execution
    }

    @Test
    fun `an over-balance confirmation sends nothing`() {
        val conversationId = UUID.randomUUID()
        stage(conversationId, "5000.00") // exceeds account 1's balance of 1000

        val result = runBlocking { agentService.reply(1, conversationId, "yes") }

        assertEquals(InteractionType.ANSWER, result.type)
        assertTrue(result.reply.contains("exceed your balance"))
        assertEquals(0, accounts.findById(1).get().balance.compareTo(BigDecimal("1000.00"))) // unchanged
    }

    @Test
    fun `denying a staged transfer sends nothing and clears it`() {
        val conversationId = UUID.randomUUID()
        stage(conversationId, "100.00")

        val result = runBlocking { agentService.reply(1, conversationId, "no thanks, cancel") }

        assertEquals(InteractionType.ANSWER, result.type)
        assertEquals(0, accounts.findById(1).get().balance.compareTo(BigDecimal("1000.00"))) // unchanged
        assertNull(pending.get(conversationId))
    }
}
