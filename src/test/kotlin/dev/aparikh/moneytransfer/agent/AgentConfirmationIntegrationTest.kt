package dev.aparikh.moneytransfer.agent

import dev.aparikh.moneytransfer.account.AccountRepository
import dev.aparikh.moneytransfer.transfer.TransferService
import dev.aparikh.moneytransfer.transfer.TransferStatus
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

    @Autowired
    lateinit var transferService: TransferService

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
    fun `affirming a staged transfer reserves the money in Postgres and queues settlement`() {
        val conversationId = UUID.randomUUID()
        stage(conversationId, "100.00") // seed balances: account 1 = 1000, account 2 = 500

        val result = runBlocking { agentService.reply(accountId = 1, conversationId = conversationId, answer = "yes") }

        assertEquals(InteractionType.ANSWER, result.type)
        // Step 7: the "yes" debits the sender and queues the transfer; the reply says so.
        assertTrue(result.reply.contains("Queued"))
        assertEquals(0, accounts.findById(1).get().balance.compareTo(BigDecimal("900.00")), "sender debited on confirm")
        assertEquals(0, accounts.findById(2).get().balance.compareTo(BigDecimal("500.00")), "recipient untouched while PENDING")
        assertNull(pending.get(conversationId)) // pending cleared after execution

        // Settlement completes the credit.
        val queued = transferService.latestPendingFor(1)!!
        transferService.settle(requireNotNull(queued.id))
        assertEquals(0, accounts.findById(2).get().balance.compareTo(BigDecimal("600.00")), "recipient credited at settlement")
    }

    @Test
    fun `affirming a staged cancellation refunds the sender in Postgres`() {
        // Queue a transfer as the confirm-gate would (sender 1 → 900.00)…
        val queued = transferService.initiate(1, 2, BigDecimal("100.00"), "oops")
        // …then stage its cancellation as the undoLastTransfer tool would.
        val conversationId = UUID.randomUUID()
        pending.put(
            conversationId,
            PendingInteraction.CancelConfirmation(requireNotNull(queued.id), "Cancel your $100.00 transfer to Alice Smith"),
        )

        val result = runBlocking { agentService.reply(accountId = 1, conversationId = conversationId, answer = "yes") }

        assertEquals(InteractionType.ANSWER, result.type)
        assertEquals(0, accounts.findById(1).get().balance.compareTo(BigDecimal("1000.00")), "sender refunded")
        assertEquals(0, accounts.findById(2).get().balance.compareTo(BigDecimal("500.00")), "recipient never touched")
        assertEquals(TransferStatus.CANCELLED, transferService.transfersFor(1).first { it.id == queued.id }.status)
        assertNull(pending.get(conversationId))
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
