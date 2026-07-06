package dev.aparikh.moneytransfer.agent

import ai.koog.agents.chatMemory.feature.InMemoryChatHistoryProvider
import ai.koog.agents.snapshot.providers.InMemoryPersistenceStorageProvider
import ai.koog.agents.testing.tools.getMockExecutor
import dev.aparikh.moneytransfer.account.AccountService
import dev.aparikh.moneytransfer.agent.config.AgentModelProperties
import dev.aparikh.moneytransfer.agent.config.HistoryCompressionProperties
import dev.aparikh.moneytransfer.agent.config.ObservabilityProperties
import dev.aparikh.moneytransfer.agent.hitl.AffirmationInterpreter
import dev.aparikh.moneytransfer.agent.hitl.InMemoryPendingInteractionRepository
import dev.aparikh.moneytransfer.agent.hitl.PendingInteractionStore
import dev.aparikh.moneytransfer.contact.ContactService
import dev.aparikh.moneytransfer.transfer.TransferProperties
import dev.aparikh.moneytransfer.transfer.TransferService
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Step 8: proves [AgentService]'s `turnStrategy` swap — `singleRunStrategy()` when history
 * compression is disabled, `singleRunStrategyWithHistoryCompression(...)` when enabled — still
 * lets a `/chat` turn complete, for both settings.
 *
 * Incidentally the first test in the suite to exercise [AgentService.chat] (and so `runAgent`)
 * at all: [AgentServiceTest]'s existing cases only exercise `reply()`/`status()`, which resolve
 * app-side and never build an [ai.koog.agents.core.agent.AIAgent]. Closing that gap here — not
 * just for step 8's own code — is why this is a full turn through `chat()`, not a unit test of
 * a private method.
 *
 * **Not attempted:** asserting the semantic content of an actual fact-extraction LLM call — that
 * needs a real model. Per `plan.md`, verified manually (same precedent as step 6's AC-19b).
 */
class AgentHistoryCompressionTest {

    private val accountService = mockk<AccountService> {
        every { getBalance(1) } returns BigDecimal("1000.00")
    }
    private val contactService = mockk<ContactService>()
    private val transferService = mockk<TransferService>(relaxed = true)
    private val interpreter = mockk<AffirmationInterpreter>()

    private fun serviceWith(historyCompression: HistoryCompressionProperties) = AgentService(
        getMockExecutor { mockLLMAnswer("Sure, how can I help?").asDefaultResponse },
        InMemoryChatHistoryProvider(),
        InMemoryPersistenceStorageProvider(),
        PendingInteractionStore(InMemoryPendingInteractionRepository()),
        contactService,
        accountService,
        transferService,
        TransferProperties(),
        interpreter,
        ObservabilityProperties(),
        properties = AgentModelProperties(),
        historyCompression = historyCompression,
    )

    @Test
    fun `a turn completes normally with history compression enabled`() {
        val service = serviceWith(HistoryCompressionProperties(enabled = true, maxMessages = 20))

        val result = runBlocking { service.chat(accountId = 1, message = "hi", conversationId = null) }

        assertEquals(InteractionType.ANSWER, result.type)
        assertEquals("Sure, how can I help?", result.reply)
    }

    @Test
    fun `a turn completes normally with history compression disabled`() {
        val service = serviceWith(HistoryCompressionProperties(enabled = false))

        val result = runBlocking { service.chat(accountId = 1, message = "hi", conversationId = null) }

        assertEquals(InteractionType.ANSWER, result.type)
        assertEquals("Sure, how can I help?", result.reply)
    }
}
