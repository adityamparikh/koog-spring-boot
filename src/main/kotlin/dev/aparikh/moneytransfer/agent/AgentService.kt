package dev.aparikh.moneytransfer.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.modelsById
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import dev.aparikh.moneytransfer.common.AgentUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.UUID

/** The agent's reply plus the conversation it belongs to (new or continued). */
data class AgentChatResult(val reply: String, val conversationId: UUID)

/**
 * Single-turn conversational agent over Koog's auto-configured LLM executors.
 *
 * Koog concepts used here:
 * - **`AIAgent`** — a runnable agent built from a [PromptExecutor] + [LLModel]. With the
 *   default single-run strategy and no tools (tools arrive at step 3), `run(input)` sends one
 *   prompt and returns the assistant's text. https://docs.koog.ai/single-run-agents/
 * - **Auto-configured executors** — `anthropicExecutor` / `openAIExecutor` are beans from the
 *   Koog Spring Boot starter, each already wrapping its client in a `RetryingLLMClient` that
 *   retries transient 429/5xx/timeout errors on the *same* provider.
 *   https://docs.koog.ai/spring-boot/
 *
 * **Why the fallback is hand-rolled:** Koog's `MultiLLMPromptExecutor.fallback` only routes
 * when *no client is registered* for a provider — it does not catch a registered client's
 * runtime error. So cross-provider failover (feature.md FR-07/AC-28: Anthropic error → retry
 * on OpenAI `gpt-5.4`) is implemented here with an explicit try/catch, sitting *above*
 * Koog's same-provider retry. https://docs.koog.ai/prompts/prompt-executors/
 */
@Service
class AgentService(
    // The starter names these beans by method name; disambiguate the two PromptExecutor beans.
    // Nullable = optional: the starter only creates a provider's executor when its api-key is
    // non-blank, so the app still boots with one provider or none (a chat then fails cleanly).
    @param:Qualifier("anthropicExecutor") private val anthropicExecutor: PromptExecutor?,
    @param:Qualifier("openAIExecutor") private val openAIExecutor: PromptExecutor?,
    private val conversations: ConversationStore,
    properties: AgentModelProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    // Resolve model ids → LLModel at construction so a typo'd property fails the context on
    // startup, not on the first request. `anthropicComplexModel` is validated now but only
    // routed to by step 4's custom strategy.
    private val anthropicModel = resolve(AnthropicModels, properties.anthropicModel)
    private val anthropicComplexModel = resolve(AnthropicModels, properties.anthropicComplexModel)
    private val openAiFallbackModel = resolve(OpenAIModels, properties.openAiFallbackModel)

    init {
        logger.info(
            "Agent models — primary={}, complex={}, fallback={}",
            anthropicModel.id, anthropicComplexModel.id, openAiFallbackModel.id,
        )
    }

    /**
     * Runs one conversational turn for [accountId] within [conversationId] (a new one is
     * created when null), replaying prior turns as context. Tries Anthropic first, falling
     * back to OpenAI on any Anthropic error.
     *
     * @throws AgentUnavailableException if both providers fail.
     */
    fun chat(accountId: Long, message: String, conversationId: UUID?): AgentChatResult {
        val id = conversationId ?: UUID.randomUUID()
        val input = renderInput(accountId, conversations.historyOf(id), message)

        // Ordered provider attempts: Anthropic first (its RetryingLLMClient already absorbs
        // transient faults), then OpenAI as the cross-provider error-fallback. Providers with
        // no configured key are simply absent from the list.
        val attempts = buildList {
            anthropicExecutor?.let { add(it to anthropicModel) }
            openAIExecutor?.let { add(it to openAiFallbackModel) }
        }
        if (attempts.isEmpty()) {
            throw AgentUnavailableException(IllegalStateException("No LLM provider is configured"))
        }

        var lastError: Exception? = null
        for ((index, attempt) in attempts.withIndex()) {
            val (executor, model) = attempt
            try {
                val reply = runAgent(executor, model, input)
                conversations.append(id, Turn(Role.USER, message), Turn(Role.ASSISTANT, reply))
                return AgentChatResult(reply, id)
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: Exception) {
                lastError = e
                val next = attempts.getOrNull(index + 1)
                if (next != null) {
                    logger.warn("Agent call on {} failed; falling back to {}", model.id, next.second.id, e)
                } else {
                    logger.error("Agent call on {} failed and no fallback remains", model.id, e)
                }
            }
        }
        throw AgentUnavailableException(lastError!!)
    }

    /**
     * Builds and runs a single-run [AIAgent] over [executor] with [model].
     *
     * `AIAgent.run` is a `suspend` function; we bridge it with [runBlocking] rather than a
     * `suspend` controller so no reactive/coroutine-MVC plumbing is needed. Virtual threads
     * (`spring.threads.virtual.enabled=true`) make this blocking call cheap.
     */
    private fun runAgent(executor: PromptExecutor, model: LLModel, input: String): String =
        runBlocking {
            // A fresh single-run agent per turn; conversation continuity is supplied via `input`
            // (see renderInput) until step 3 introduces Koog checkpoint-backed sessions.
            val agent = AIAgent(promptExecutor = executor, llmModel = model, systemPrompt = SYSTEM_PROMPT)
            agent.run(input)
        }

    /**
     * Renders the system-visible input for a turn: prior turns (if any) followed by the new
     * message. Internal so it can be unit-tested directly. The acting [accountId] is included
     * as lightweight context; it becomes meaningful at step 3 when tools act on that account.
     */
    internal fun renderInput(accountId: Long, history: List<Turn>, message: String): String {
        if (history.isEmpty()) return message
        val transcript = history.joinToString("\n") { turn ->
            val speaker = if (turn.role == Role.USER) "User" else "Assistant"
            "$speaker: ${turn.content}"
        }
        return buildString {
            append("Conversation so far (account $accountId):\n")
            append(transcript)
            append("\n\nUser: ")
            append(message)
        }
    }

    private fun resolve(definitions: LLModelDefinitions, modelId: String): LLModel =
        definitions.modelsById()[modelId]
            ?: error(
                "Unknown Koog model id '$modelId'. Known ids: ${definitions.modelsById().keys.sorted()}",
            )

    private companion object {
        // Kept minimal for step 2 (no tools yet); step 3 expands this as tools/HITL are added.
        private const val SYSTEM_PROMPT =
            "You are a helpful money-transfer assistant. Answer the user's questions concisely."
    }
}
