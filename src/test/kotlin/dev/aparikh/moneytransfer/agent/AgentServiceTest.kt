package dev.aparikh.moneytransfer.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.testing.tools.getMockExecutor
import dev.aparikh.moneytransfer.common.AgentUnavailableException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * Unit tests for [AgentService] using Koog's `agents-test` mock executor — no real LLM API is
 * ever called. Proves the happy path (AC-10) and the hand-rolled Anthropic→OpenAI fallback
 * (AC-28).
 */
class AgentServiceTest {

    private val conversations = ConversationStore()
    private val properties = AgentModelProperties() // defaults: claude-sonnet-4-6 / claude-opus-4-7 / gpt-5.4

    /** A PromptExecutor whose every call fails — stands in for a downed/erroring provider. */
    private class FailingExecutor : PromptExecutor() {
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            throw IllegalStateException("provider down")

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            throw IllegalStateException("provider down")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw IllegalStateException("provider down")

        override fun close() {}
    }

    @Test
    fun `chat returns the LLM reply and a stable conversation id`() {
        val anthropic = getMockExecutor { mockLLMAnswer("Hello from Anthropic!").asDefaultResponse }
        val openai = getMockExecutor { mockLLMAnswer("unused").asDefaultResponse }
        val service = AgentService(anthropic, openai, conversations, properties)

        val result = service.chat(accountId = 1, message = "Hi", conversationId = null)

        assertEquals("Hello from Anthropic!", result.reply)
        assertNotNull(result.conversationId)
        // The turn is recorded so the next call can replay it as context.
        assertEquals(2, conversations.historyOf(result.conversationId).size)
    }

    @Test
    fun `chat falls back to OpenAI when the Anthropic call errors`() {
        val anthropic = FailingExecutor()
        val openai = getMockExecutor { mockLLMAnswer("Hello from OpenAI!").asDefaultResponse }
        val service = AgentService(anthropic, openai, conversations, properties)

        val result = service.chat(accountId = 1, message = "Hi", conversationId = null)

        assertEquals("Hello from OpenAI!", result.reply)
    }

    @Test
    fun `chat throws AgentUnavailable when both providers fail`() {
        val service = AgentService(FailingExecutor(), FailingExecutor(), conversations, properties)

        assertThrows<AgentUnavailableException> {
            service.chat(accountId = 1, message = "Hi", conversationId = null)
        }
    }

    @Test
    fun `renderInput returns the bare message for a new conversation`() {
        val service = AgentService(
            getMockExecutor { mockLLMAnswer("x").asDefaultResponse },
            getMockExecutor { mockLLMAnswer("x").asDefaultResponse },
            conversations, properties,
        )

        assertEquals("Send 50 to Alice", service.renderInput(1, emptyList(), "Send 50 to Alice"))
    }

    @Test
    fun `renderInput replays prior turns as context`() {
        val service = AgentService(
            getMockExecutor { mockLLMAnswer("x").asDefaultResponse },
            getMockExecutor { mockLLMAnswer("x").asDefaultResponse },
            conversations, properties,
        )
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
