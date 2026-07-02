package dev.aparikh.moneytransfer.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import dev.aparikh.moneytransfer.common.AgentUnavailableException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [AgentService].
 *
 * The happy path uses Koog's `agents-test` mock executor (no real LLM). The fallback test
 * needs per-provider behavior — the mock executor matches on prompt text and ignores the
 * model, so it cannot selectively fail one provider — so it uses a small routing fake
 * executor that fails Anthropic-model calls and answers OpenAI-model calls, proving the
 * hand-rolled cross-provider failover (AC-28).
 */
class AgentServiceTest {

    private val conversations = ConversationStore()
    private val properties = AgentModelProperties() // defaults: claude-sonnet-4-6 / claude-opus-4-7 / gpt-5.4

    /**
     * A [PromptExecutor] that routes by `model.provider`: Anthropic calls throw (simulating a
     * downed primary), OpenAI calls succeed. A hand-written subclass rather than a mock —
     * `LLMClient`/`PromptExecutor` are multiplatform `expect` types whose suspend members
     * can't be proxied by mockk.
     */
    private class ProviderRoutingFakeExecutor : PromptExecutor() {
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            if (model.provider == LLMProvider.Anthropic) {
                throw IllegalStateException("Anthropic down")
            } else {
                Message.Assistant("Hello from OpenAI!", ResponseMetaInfo.Empty)
            }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            throw UnsupportedOperationException("not used")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw UnsupportedOperationException("not used")

        override fun close() {}
    }

    @Test
    fun `chat returns the LLM reply and a stable conversation id`() {
        val executor = getMockExecutor { mockLLMAnswer("Hello from Anthropic!").asDefaultResponse }
        val service = AgentService(executor, conversations, properties)

        val result = runBlocking { service.chat(accountId = 1, message = "Hi", conversationId = null) }

        assertEquals("Hello from Anthropic!", result.reply)
        assertNotNull(result.conversationId)
        // The turn is recorded so the next call can replay it as context.
        assertEquals(2, conversations.historyOf(result.conversationId).size)
    }

    @Test
    fun `chat falls back to OpenAI when the Anthropic call errors`() {
        val service = AgentService(ProviderRoutingFakeExecutor(), conversations, properties)

        val result = runBlocking { service.chat(accountId = 1, message = "Hi", conversationId = null) }

        assertEquals("Hello from OpenAI!", result.reply)
    }

    @Test
    fun `chat throws AgentUnavailable when no provider can serve the request`() {
        // An executor with no registered clients: every model in the chain throws "no client".
        val service = AgentService(MultiLLMPromptExecutor(emptyMap()), conversations, properties)

        assertThrows<AgentUnavailableException> {
            runBlocking { service.chat(accountId = 1, message = "Hi", conversationId = null) }
        }
    }

    @Test
    fun `renderInput returns the bare message for a new conversation`() {
        val service = AgentService(getMockExecutor { mockLLMAnswer("x").asDefaultResponse }, conversations, properties)

        assertEquals("Send 50 to Alice", service.renderInput(1, emptyList(), "Send 50 to Alice"))
    }

    @Test
    fun `renderInput replays prior turns as context`() {
        val service = AgentService(getMockExecutor { mockLLMAnswer("x").asDefaultResponse }, conversations, properties)
        val history = listOf(
            Turn(Role.USER, "Who are my contacts?"),
            Turn(Role.ASSISTANT, "Alice, Bob, and two Daniels."),
        )

        val input = service.renderInput(1, history, "Send 50 to Alice")

        assertTrue(input.contains("Who are my contacts?"))
        assertTrue(input.contains("Alice, Bob, and two Daniels."))
        assertTrue(input.trimEnd().endsWith("Send 50 to Alice"))
    }
}
