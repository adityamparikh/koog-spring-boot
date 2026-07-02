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
 * - **`multiLLMPromptExecutor`** — the aggregate executor bean from the Koog Spring Boot
 *   starter. It holds one (retry-wrapped) client per configured provider and dispatches each
 *   call by `model.provider`. https://docs.koog.ai/spring-boot/
 *
 * **Why the fallback is hand-rolled:** this mirrors Koog's own documented `RobustAIService`
 * pattern (docs.koog.ai/spring-boot) — loop over models from different providers and
 * try/catch each call on `multiLLMPromptExecutor`. It is *not* `MultiLLMPromptExecutor.fallback`
 * (`FallbackPromptExecutorSettings`), which only routes when *no client is registered* for a
 * provider and never catches a registered client's runtime error. So cross-provider failover
 * (feature.md FR-07/AC-28: Anthropic error → retry on OpenAI `gpt-5.4`) sits here, *above*
 * Koog's per-provider `RetryingLLMClient` retry. https://docs.koog.ai/prompts/prompt-executors/
 */
@Service
class AgentService(
    // The single aggregate executor (all configured providers). It always exists — even with
    // zero API keys the starter creates it with an empty client set — so no nullable handling
    // is needed: a call for an unconfigured provider simply throws and the loop moves on.
    @param:Qualifier("multiLLMPromptExecutor") private val promptExecutor: PromptExecutor,
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

    // Ordered fallback chain: Anthropic first, then OpenAI. Each entry dispatches to its
    // provider via `promptExecutor`; a failed attempt falls through to the next model.
    private val fallbackChain = listOf(anthropicModel, openAiFallbackModel)

    init {
        logger.info(
            "Agent models — primary={}, complex={}, fallback={}",
            anthropicModel.id, anthropicComplexModel.id, openAiFallbackModel.id,
        )
    }

    /**
     * Runs one conversational turn for [accountId] within [conversationId] (a new one is
     * created when null), replaying prior turns as context. Walks [fallbackChain]: Anthropic
     * first, then OpenAI on any Anthropic error.
     *
     * `suspend` all the way — Koog's `AIAgent.run` is suspending, and Spring MVC invokes
     * suspending controller methods directly (via `kotlinx-coroutines-reactor`, on the
     * classpath), so no `runBlocking` bridge is needed.
     *
     * @throws AgentUnavailableException if every provider in the chain fails.
     */
    suspend fun chat(accountId: Long, message: String, conversationId: UUID?): AgentChatResult {
        val id = conversationId ?: UUID.randomUUID()
        val input = renderInput(accountId, conversations.historyOf(id), message)

        var lastError: Exception? = null
        for ((index, model) in fallbackChain.withIndex()) {
            try {
                val reply = runAgent(model, input)
                conversations.append(id, Turn(Role.USER, message), Turn(Role.ASSISTANT, reply))
                return AgentChatResult(reply, id)
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: Exception) {
                lastError = e
                val next = fallbackChain.getOrNull(index + 1)
                if (next != null) {
                    logger.warn("Agent call on {} failed; falling back to {}", model.id, next.id, e)
                } else {
                    logger.error("Agent call on {} failed and no fallback remains", model.id, e)
                }
            }
        }
        throw AgentUnavailableException(lastError ?: IllegalStateException("No LLM provider is configured"))
    }

    /**
     * Builds and runs a single-run [AIAgent] with [model] over the aggregate [promptExecutor],
     * which routes the call to that model's provider.
     */
    private suspend fun runAgent(model: LLModel, input: String): String {
        // A fresh single-run agent per turn; conversation continuity is supplied via `input`
        // (see renderInput) until step 3 introduces Koog checkpoint-backed sessions.
        val agent = AIAgent(promptExecutor = promptExecutor, llmModel = model, systemPrompt = SYSTEM_PROMPT)
        return agent.run(input)
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
