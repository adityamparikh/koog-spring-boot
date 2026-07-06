package dev.aparikh.moneytransfer.agent

import ai.koog.agents.chatMemory.feature.InMemoryChatHistoryProvider
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.GraphCheckpointProperties
import ai.koog.agents.snapshot.feature.tombstoneCheckpoint
import ai.koog.agents.snapshot.providers.InMemoryPersistenceStorageProvider
import ai.koog.agents.testing.tools.getMockExecutor
import dev.aparikh.moneytransfer.agent.config.AgentModelProperties
import dev.aparikh.moneytransfer.agent.config.HistoryCompressionProperties
import dev.aparikh.moneytransfer.agent.config.ObservabilityProperties
import dev.aparikh.moneytransfer.agent.hitl.Affirmation
import dev.aparikh.moneytransfer.agent.hitl.AffirmationInterpreter
import dev.aparikh.moneytransfer.agent.hitl.InMemoryPendingInteractionRepository
import dev.aparikh.moneytransfer.agent.hitl.PendingInteraction
import dev.aparikh.moneytransfer.agent.hitl.PendingInteractionStore
import dev.aparikh.moneytransfer.agent.hitl.StagedTransfer
import dev.aparikh.moneytransfer.common.InsufficientFundsException
import dev.aparikh.moneytransfer.common.NoPendingInteractionException
import dev.aparikh.moneytransfer.common.TransferNotCancellableException
import dev.aparikh.moneytransfer.contact.ContactService
import dev.aparikh.moneytransfer.transfer.TransferProperties
import dev.aparikh.moneytransfer.transfer.TransferService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests the deterministic, app-side parts of [AgentService] — the confirm-gate that guarantees
 * "no money moves without an affirmation" (AC-13) and the reply routing. The LLM-driven tool
 * loop is covered by [MoneyTransferToolsTest] (tool behaviour) and the mock-executor flow test;
 * here the agent is never run (reply resolves confirmations app-side). Koog's in-memory ChatMemory
 * and Persistence providers stand in for the Postgres-backed ones used in production.
 */
class AgentServiceTest {

    private val executor = getMockExecutor { mockLLMAnswer("hi").asDefaultResponse }
    private val pending = PendingInteractionStore(InMemoryPendingInteractionRepository())
    private val chatHistory = InMemoryChatHistoryProvider()
    private val checkpointStorage = InMemoryPersistenceStorageProvider()
    private val contactService = mockk<ContactService>()
    private val accountService = mockk<dev.aparikh.moneytransfer.account.AccountService>()
    private val transferService = mockk<TransferService>(relaxed = true)
    private val interpreter = mockk<AffirmationInterpreter>()
    private val service = AgentService(
        executor, chatHistory, checkpointStorage, pending, contactService, accountService, transferService,
        TransferProperties(), interpreter, ObservabilityProperties(), properties = AgentModelProperties(),
        historyCompression = HistoryCompressionProperties(),
    )

    private val conversationId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    private fun stageConfirmation() = pending.put(
        conversationId,
        PendingInteraction.Confirmation(
            StagedTransfer(senderAccountId = 1, recipientAccountId = 2, recipientDisplay = "Alice Smith", amount = BigDecimal("50.00"), purpose = "lunch"),
        ),
    )

    @Test
    fun `affirming a confirmation initiates the staged transfer once and reports the undo window`() {
        stageConfirmation()
        coEvery { interpreter.interpret("yeah go for it") } returns Affirmation.AFFIRM

        val result = runBlocking { service.reply(accountId = 1, conversationId = conversationId, answer = "yeah go for it") }

        assertEquals(InteractionType.ANSWER, result.type)
        verify(exactly = 1) { transferService.initiate(1, 2, BigDecimal("50.00"), "lunch") }
        // Step 7: settlement is async — the reply says "queued", names the window, and offers undo.
        assertTrue(result.reply.contains("Queued"))
        assertTrue(result.reply.contains("undo"))
        assertNull(pending.get(conversationId))
    }

    @Test
    fun `denying a confirmation transfers nothing and clears it`() {
        stageConfirmation()
        coEvery { interpreter.interpret("no, cancel that") } returns Affirmation.DENY

        val result = runBlocking { service.reply(1, conversationId, "no, cancel that") }

        assertEquals(InteractionType.ANSWER, result.type)
        verify(exactly = 0) { transferService.initiate(any(), any(), any(), any()) }
        assertNull(pending.get(conversationId))
    }

    @Test
    fun `an unclear confirmation reply re-prompts and transfers nothing`() {
        stageConfirmation()
        coEvery { interpreter.interpret(any()) } returns Affirmation.UNCLEAR

        val result = runBlocking { service.reply(1, conversationId, "what were the details?") }

        assertEquals(InteractionType.CONFIRMATION, result.type)
        assertNotNull(result.transferSummary)
        verify(exactly = 0) { transferService.initiate(any(), any(), any(), any()) }
        assertNotNull(pending.get(conversationId)) // still pending
    }

    @Test
    fun `affirming an over-balance transfer reports the failure and moves no money`() {
        stageConfirmation()
        coEvery { interpreter.interpret("yes") } returns Affirmation.AFFIRM
        every { transferService.initiate(1, 2, BigDecimal("50.00"), "lunch") } throws InsufficientFundsException(1, BigDecimal("50.00"))

        val result = runBlocking { service.reply(1, conversationId, "yes") }

        assertEquals(InteractionType.ANSWER, result.type)
        assertTrue(result.reply.contains("exceed your balance"))
        assertNull(pending.get(conversationId))
    }

    @Test
    fun `replying with nothing pending is rejected`() {
        assertThrows<NoPendingInteractionException> {
            runBlocking { service.reply(1, UUID.randomUUID(), "yes") }
        }
    }

    // --- step 7: the undo confirmation goes through the same deterministic gate ---

    private fun stageCancelConfirmation() = pending.put(
        conversationId,
        PendingInteraction.CancelConfirmation(transferId = 42, summary = "Cancel your $50.00 transfer to Alice Smith"),
    )

    @Test
    fun `affirming a cancel confirmation cancels the transfer once and clears it`() {
        stageCancelConfirmation()
        coEvery { interpreter.interpret("yes") } returns Affirmation.AFFIRM

        val result = runBlocking { service.reply(accountId = 1, conversationId = conversationId, answer = "yes") }

        assertEquals(InteractionType.ANSWER, result.type)
        verify(exactly = 1) { transferService.cancel(42, 1) }
        assertNull(pending.get(conversationId))
    }

    @Test
    fun `affirming a cancel that lost the race to the settler reports it settled`() {
        stageCancelConfirmation()
        coEvery { interpreter.interpret("yes") } returns Affirmation.AFFIRM
        every { transferService.cancel(42, 1) } throws TransferNotCancellableException(42, "SETTLED")

        val result = runBlocking { service.reply(1, conversationId, "yes") }

        assertEquals(InteractionType.ANSWER, result.type)
        assertTrue(result.reply.contains("already settled"))
        assertNull(pending.get(conversationId))
    }

    @Test
    fun `denying a cancel confirmation leaves the transfer queued`() {
        stageCancelConfirmation()
        coEvery { interpreter.interpret("no") } returns Affirmation.DENY

        val result = runBlocking { service.reply(1, conversationId, "no") }

        assertEquals(InteractionType.ANSWER, result.type)
        verify(exactly = 0) { transferService.cancel(any(), any()) }
        assertNull(pending.get(conversationId))
    }

    // --- status: last-run state from the Persistence checkpoint tombstone ---

    @Test
    fun `status reports NONE for a conversation with no agent runs`() {
        val result = runBlocking { service.status(UUID.randomUUID()) }

        assertEquals(LastRunState.NONE, result.lastRun)
    }

    @Test
    fun `status reports COMPLETED when the latest checkpoint is the tombstone`() {
        runBlocking {
            checkpointStorage.saveCheckpoint(
                conversationId.toString(),
                tombstoneCheckpoint(createdAt = Clock.System.now(), version = 1),
            )

            assertEquals(LastRunState.COMPLETED, service.status(conversationId).lastRun)
        }
    }

    @Test
    fun `status reports INTERRUPTED when a mid-run checkpoint was never tombstoned`() {
        runBlocking {
            checkpointStorage.saveCheckpoint(
                conversationId.toString(),
                AgentCheckpointData(
                    checkpointId = "cp-mid-run",
                    createdAt = Clock.System.now(),
                    messageHistory = emptyList(),
                    version = 1,
                    graphProperties = GraphCheckpointProperties(nodePath = "nodeCallLLM"),
                ),
            )

            assertEquals(LastRunState.INTERRUPTED, service.status(conversationId).lastRun)
        }
    }

    @Test
    fun `an unclear cancel reply re-prompts with the cancellation summary`() {
        stageCancelConfirmation()
        coEvery { interpreter.interpret(any()) } returns Affirmation.UNCLEAR

        val result = runBlocking { service.reply(1, conversationId, "hmm") }

        assertEquals(InteractionType.CONFIRMATION, result.type)
        assertNotNull(result.transferSummary)
        verify(exactly = 0) { transferService.cancel(any(), any()) }
        assertNotNull(pending.get(conversationId)) // still awaiting a clear yes/no
    }
}
