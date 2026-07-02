package dev.aparikh.moneytransfer.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
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
 * Single-turn conversational agent with multi-LLM fallback.
 *
 * This follows Koog's documented **`RobustAIService.generateWithFallback`** pattern
 * (https://docs.koog.ai/spring-boot/#llm-provider-fallback): inject the aggregate
 * `multiLLMPromptExecutor` bean and **iterate over a list of models from different
 * providers**, calling `execute(prompt, model)` on each in a try/catch. Because the executor
 * dispatches by `model.provider`, each iteration targets a different provider, so a failed
 * call falls through to the next.
 *
 * **Why this hand-rolled loop and not `MultiLLMPromptExecutor.fallback`:** the built-in
 * `FallbackPromptExecutorSettings` only routes when *no client is registered* for a provider —
 * it never catches a registered client's runtime error. The per-provider `RetryingLLMClient`
 * (inside each executor) handles transient same-provider retries; this loop handles
 * cross-provider failover (feature.md FR-07/AC-28: Anthropic error → OpenAI `gpt-5.4`).
 */
@Service
class AgentService(
    // The single aggregate executor holding every configured provider's (retry-wrapped) client.
    // It always exists — even with zero API keys the starter creates it with an empty client
    // set — so an unconfigured provider just throws and the loop advances to the next model.
    @param:Qualifier("multiLLMPromptExecutor") private val multiLLMPromptExecutor: PromptExecutor,
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

    // The ordered fallback chain (the `llms` list in Koog's RobustAIService): Anthropic first,
    // OpenAI second. Each dispatches to its provider via `multiLLMPromptExecutor`.
    private val llms: List<LLModel> = listOf(anthropicModel, openAiFallbackModel)

    init {
        logger.info(
            "Agent models — primary={}, complex={}, fallback={}",
            anthropicModel.id, anthropicComplexModel.id, openAiFallbackModel.id,
        )
    }

    /**
     * Runs one conversational turn for [accountId] within [conversationId] (a new one is
     * created when null), replaying prior turns as prompt context. Walks [llms]: Anthropic
     * first, then OpenAI on any Anthropic error.
     *
     * `suspend` all the way — `PromptExecutor.execute` is suspending and Spring MVC invokes
     * suspending controller methods directly, so there is no `runBlocking` bridge.
     *
     * @throws AgentUnavailableException if every provider in [llms] fails.
     */
    suspend fun chat(accountId: Long, message: String, conversationId: UUID?): AgentChatResult {
        val id = conversationId ?: UUID.randomUUID()
        val prompt = buildPrompt(accountId, conversations.historyOf(id), message)

        var lastError: Exception? = null
        for ((index, model) in llms.withIndex()) {
            try {
                // execute() dispatches to model.provider's client; textContent() concatenates
                // the assistant message's text parts (Koog 1.0.0 returns a single Message.Assistant).
                val reply = multiLLMPromptExecutor.execute(prompt, model).textContent()
                conversations.append(id, Turn(Role.USER, message), Turn(Role.ASSISTANT, reply))
                return AgentChatResult(reply, id)
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: Exception) {
                lastError = e
                val next = llms.getOrNull(index + 1)
                if (next != null) {
                    logger.warn("LLM call on {} failed; falling back to {}", model.id, next.id, e)
                } else {
                    logger.error("LLM call on {} failed and no fallback remains", model.id, e)
                }
            }
        }
        throw AgentUnavailableException(lastError ?: IllegalStateException("No LLM provider is configured"))
    }

    /**
     * Builds the prompt for a turn: a system message (carrying the acting [accountId] as
     * context — tools will act on it from step 3), the prior turns as proper user/assistant
     * messages, then the new user message. Internal so it can be unit-tested.
     */
    internal fun buildPrompt(accountId: Long, history: List<Turn>, message: String): Prompt =
        prompt("money-transfer-chat") {
            system("$SYSTEM_PROMPT You are assisting account $accountId.")
            history.forEach { turn ->
                when (turn.role) {
                    Role.USER -> user(turn.content)
                    Role.ASSISTANT -> assistant(turn.content)
                }
            }
            user(message)
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
