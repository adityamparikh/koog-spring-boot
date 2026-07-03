package dev.aparikh.moneytransfer.agent

import ai.koog.agents.chatMemory.feature.InMemoryChatHistoryProvider
import ai.koog.agents.snapshot.providers.InMemoryPersistenceStorageProvider
import ai.koog.agents.testing.tools.getMockExecutor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.aparikh.moneytransfer.common.InsufficientFundsException
import dev.aparikh.moneytransfer.common.NoPendingInteractionException
import dev.aparikh.moneytransfer.contact.ContactService
import dev.aparikh.moneytransfer.transfer.TransferService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

/**
 * Tests the deterministic, app-side parts of [AgentService] — the confirm-gate that guarantees
 * "no money moves without an affirmation" (AC-13) and the reply routing. The LLM-driven tool
 * loop is covered by [MoneyTransferToolsTest] (tool behaviour) and the mock-executor flow test;
 * here the agent is never run (reply resolves confirmations app-side). Koog's in-memory ChatMemory
 * and Persistence providers stand in for the Postgres-backed ones used in production.
 */
class AgentServiceTest {

    private val executor = getMockExecutor { mockLLMAnswer("hi").asDefaultResponse }
    private val pending = PendingInteractionStore(InMemoryPendingInteractionRepository(), jacksonObjectMapper())
    private val chatHistory = InMemoryChatHistoryProvider()
    private val checkpointStorage = InMemoryPersistenceStorageProvider()
    private val contactService = mockk<ContactService>()
    private val accountService = mockk<dev.aparikh.moneytransfer.account.AccountService>()
    private val transferService = mockk<TransferService>(relaxed = true)
    private val interpreter = mockk<AffirmationInterpreter>()
    private val service = AgentService(
        executor, chatHistory, checkpointStorage, pending, contactService, accountService, transferService, interpreter, AgentModelProperties(),
    )

    private val conversationId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    private fun stageConfirmation() = pending.put(
        conversationId,
        PendingInteraction.Confirmation(
            StagedTransfer(senderAccountId = 1, recipientAccountId = 2, recipientDisplay = "Alice Smith", amount = BigDecimal("50.00"), purpose = "lunch"),
        ),
    )

    @Test
    fun `affirming a confirmation executes the staged transfer once and clears it`() {
        stageConfirmation()
        coEvery { interpreter.interpret("yeah go for it") } returns Affirmation.AFFIRM

        val result = runBlocking { service.reply(accountId = 1, conversationId = conversationId, answer = "yeah go for it") }

        assertEquals(InteractionType.ANSWER, result.type)
        verify(exactly = 1) { transferService.transfer(1, 2, BigDecimal("50.00"), "lunch") }
        assertNull(pending.get(conversationId))
    }

    @Test
    fun `denying a confirmation transfers nothing and clears it`() {
        stageConfirmation()
        coEvery { interpreter.interpret("no, cancel that") } returns Affirmation.DENY

        val result = runBlocking { service.reply(1, conversationId, "no, cancel that") }

        assertEquals(InteractionType.ANSWER, result.type)
        verify(exactly = 0) { transferService.transfer(any(), any(), any(), any()) }
        assertNull(pending.get(conversationId))
    }

    @Test
    fun `an unclear confirmation reply re-prompts and transfers nothing`() {
        stageConfirmation()
        coEvery { interpreter.interpret(any()) } returns Affirmation.UNCLEAR

        val result = runBlocking { service.reply(1, conversationId, "what were the details?") }

        assertEquals(InteractionType.CONFIRMATION, result.type)
        assertNotNull(result.transferSummary)
        verify(exactly = 0) { transferService.transfer(any(), any(), any(), any()) }
        assertNotNull(pending.get(conversationId)) // still pending
    }

    @Test
    fun `affirming an over-balance transfer reports the failure and moves no money`() {
        stageConfirmation()
        coEvery { interpreter.interpret("yes") } returns Affirmation.AFFIRM
        every { transferService.transfer(1, 2, BigDecimal("50.00"), "lunch") } throws InsufficientFundsException(1, BigDecimal("50.00"))

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
}
