package dev.aparikh.moneytransfer.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.modelsById
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import dev.aparikh.moneytransfer.common.AgentUnavailableException
import dev.aparikh.moneytransfer.common.InsufficientFundsException
import dev.aparikh.moneytransfer.common.NoPendingInteractionException
import dev.aparikh.moneytransfer.common.UnknownAccountException
import dev.aparikh.moneytransfer.common.UnknownContactException
import dev.aparikh.moneytransfer.account.AccountService
import dev.aparikh.moneytransfer.contact.ContactService
import dev.aparikh.moneytransfer.transfer.TransferService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.UUID

/** The kind of turn the API is reporting back to the caller. */
enum class InteractionType { ANSWER, CLARIFICATION, CONFIRMATION }

/**
 * The agent's tagged result — produced by [AgentService] and returned as-is by the controller
 * (it is the public JSON contract). [type] tells the client what to do next:
 * - `ANSWER` — a plain reply, nothing pending.
 * - `CLARIFICATION` — pick one of [candidates], then `POST /reply`.
 * - `CONFIRMATION` — approve/decline [transferSummary] via `POST /reply` ("yes"/"no").
 *
 * (A separate web DTO was collapsed into this — reintroduce one only if the API needs to
 * diverge from what the service returns.)
 */
data class ChatResponse(
    val reply: String,
    val conversationId: UUID,
    val type: InteractionType,
    val candidates: List<ContactCandidate> = emptyList(),
    val transferSummary: String? = null,
)

/**
 * Tool-enabled conversational agent over the money-transfer domain.
 *
 * Each `/chat` turn builds an [AIAgent] with a per-request [MoneyTransferTools] `ToolSet`
 * (registered via `ToolRegistry`), `handleEvents { … }` for observability, and the step-2
 * multi-LLM fallback loop (Anthropic → OpenAI). The default `singleRunStrategy()` drives the
 * LLM↔tool loop until the model produces a text answer. https://docs.koog.ai/tools-overview/
 *
 * HITL is plain multi-turn conversation (the transcript in [ConversationStore] is the state) plus
 * a deterministic confirm-gate: `prepareTransfer` only *stages* a transfer, and `reply(...)`
 * executes it **app-side** once the user affirms — so no money moves without an explicit "yes".
 */
@Service
class AgentService(
    @param:Qualifier("multiLLMPromptExecutor") private val multiLLMPromptExecutor: PromptExecutor,
    private val conversations: ConversationStore,
    private val pending: PendingInteractionStore,
    private val contactService: ContactService,
    private val accountService: AccountService,
    private val transferService: TransferService,
    private val affirmationInterpreter: AffirmationInterpreter,
    properties: AgentModelProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val anthropicModel = resolve(AnthropicModels, properties.anthropicModel)
    private val openAiFallbackModel = resolve(OpenAIModels, properties.openAiFallbackModel)

    // Ordered fallback chain (Koog RobustAIService pattern): Anthropic first, then OpenAI.
    private val llms: List<LLModel> = listOf(anthropicModel, openAiFallbackModel)

    /**
     * Runs one conversational turn. A fresh `/chat` starts a new intent, so any stale pending
     * clarification/confirmation for this conversation is discarded first (edge rule). Returns a
     * response tagged by what the turn produced.
     */
    suspend fun chat(accountId: Long, message: String, conversationId: UUID?): ChatResponse {
        val id = conversationId ?: UUID.randomUUID()
        pending.clear(id) // new intent — a forgotten "yes" can't fire a stale transfer later
        val input = renderConversation(conversations.historyOf(id), message)

        var lastError: Exception? = null
        for ((index, model) in llms.withIndex()) {
            try {
                val reply = runAgent(accountId, id, model, input)
                conversations.append(id, Turn(Role.USER, message), Turn(Role.ASSISTANT, reply))
                return tag(reply, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val next = llms.getOrNull(index + 1)
                if (next != null) {
                    logger.warn("Agent run on {} failed; falling back to {}", model.id, next.id, e)
                } else {
                    logger.error("Agent run on {} failed and no fallback remains", model.id, e)
                }
            }
        }
        throw AgentUnavailableException(lastError ?: IllegalStateException("No LLM provider is configured"))
    }

    /**
     * Answers whatever the conversation is awaiting. A pending **clarification** is resolved by
     * re-running the agent with [answer] as the next message (the LLM re-derives the amount from
     * the transcript). A pending **confirmation** is resolved **app-side**: affirm → execute the
     * staged transfer, deny → discard, unclear → re-prompt.
     *
     * @throws NoPendingInteractionException if nothing is awaiting a reply.
     */
    suspend fun reply(accountId: Long, conversationId: UUID, answer: String): ChatResponse =
        when (val interaction = pending.get(conversationId)) {
            is PendingInteraction.Clarification -> chat(accountId, answer, conversationId)
            is PendingInteraction.Confirmation -> resolveConfirmation(conversationId, interaction, answer)
            null -> throw NoPendingInteractionException(conversationId)
        }

    private fun resolveConfirmation(
        conversationId: UUID,
        confirmation: PendingInteraction.Confirmation,
        answer: String,
    ): ChatResponse {
        val staged = confirmation.staged
        return when (affirmationInterpreter.interpret(answer)) {
            Affirmation.AFFIRM -> {
                pending.clear(conversationId)
                val reply = try {
                    transferService.transfer(staged.senderAccountId, staged.recipientAccountId, staged.amount, staged.purpose)
                    "Done — sent $${staged.amount.toPlainString()} to ${staged.recipientDisplay}."
                } catch (e: InsufficientFundsException) {
                    // Step 3 fails honestly here; step 4 adds the "offer up to your balance" flow.
                    "That transfer would exceed your balance, so nothing was sent."
                } catch (e: UnknownAccountException) {
                    "That account no longer exists, so nothing was sent."
                }
                record(conversationId, answer, reply)
                ChatResponse(reply, conversationId, InteractionType.ANSWER)
            }

            Affirmation.DENY -> {
                pending.clear(conversationId)
                val reply = "Okay, I've cancelled that transfer. Nothing was sent."
                record(conversationId, answer, reply)
                ChatResponse(reply, conversationId, InteractionType.ANSWER)
            }

            Affirmation.UNCLEAR -> {
                val reply = "Sorry, I didn't catch that. Reply \"yes\" to ${staged.summary}, or \"no\" to cancel."
                record(conversationId, answer, reply)
                ChatResponse(reply, conversationId, InteractionType.CONFIRMATION, transferSummary = staged.summary)
            }
        }
    }

    /**
     * Builds a per-request tool-enabled [AIAgent] for [model] and runs one turn, returning the
     * assistant's final text.
     *
     * This is the densest cluster of Koog concepts in the app; each call site is annotated below.
     */
    private suspend fun runAgent(accountId: Long, conversationId: UUID, model: LLModel, input: String): String {
        // 1. Our ToolSet. Built fresh per request so the acting accountId/conversationId are
        //    captured as fields — never LLM-supplied arguments (that would be an injection risk).
        val tools = MoneyTransferTools(accountId, conversationId, contactService, accountService, pending)

        // 2. Koog ToolRegistry: `tools(instance)` reflects the @Tool methods into Tool objects
        //    (via ToolSet.asTools()) and registers them for the agent. https://docs.koog.ai/tools-overview/
        val registry = ToolRegistry { tools(tools) }

        // 3. Koog AIAgent: a runnable agent over a PromptExecutor + model. We pass the aggregate
        //    multiLLMPromptExecutor (so this model's provider is routed to) and the tool registry.
        //    With no explicit strategy it uses singleRunStrategy() — the loop that repeatedly calls
        //    the LLM, executes any tools it requests, feeds the results back, and stops when the LLM
        //    returns plain text. https://docs.koog.ai/single-run-agents/
        val agent = AIAgent(
            promptExecutor = multiLLMPromptExecutor,
            llmModel = model,
            toolRegistry = registry,
            systemPrompt = systemPrompt(accountId),
        ) {
            // 4. Koog event handlers (FR-09): observe every LLM and tool call as the run unfolds.
            //    Here we just log; the same hooks feed tracing/metrics in step 6. https://docs.koog.ai/agent-events/
            handleEvents {
                onLLMCallStarting { e -> logger.debug("LLM call starting on {} with {} tools", e.model.id, e.tools.size) }
                onLLMCallCompleted { e -> logger.debug("LLM call completed: {}", e.response?.textContent()?.take(200)) }
                onToolCallStarting { e -> logger.info("tool → {} args={}", e.toolName, e.toolArgs) }
                onToolCallCompleted { e -> logger.info("tool ← {} result={}", e.toolName, e.toolResult) }
            }
        }

        // 5. Run the turn. singleRunStrategy drives LLM↔tool iterations internally; `run` suspends
        //    until the agent produces its final text answer, which we return to the caller.
        return agent.run(input)
    }

    /** Tags a completed run by inspecting what (if anything) it left pending. */
    private fun tag(reply: String, conversationId: UUID): ChatResponse =
        when (val p = pending.get(conversationId)) {
            is PendingInteraction.Clarification ->
                ChatResponse(reply, conversationId, InteractionType.CLARIFICATION, candidates = p.candidates)
            is PendingInteraction.Confirmation ->
                ChatResponse(reply, conversationId, InteractionType.CONFIRMATION, transferSummary = p.staged.summary)
            null -> ChatResponse(reply, conversationId, InteractionType.ANSWER)
        }

    private fun record(conversationId: UUID, userMessage: String, reply: String) =
        conversations.append(conversationId, Turn(Role.USER, userMessage), Turn(Role.ASSISTANT, reply))

    /** Renders prior turns + the new message into one input string (no tool state in here). */
    internal fun renderConversation(history: List<Turn>, message: String): String {
        if (history.isEmpty()) return message
        val transcript = history.joinToString("\n") { turn ->
            val who = if (turn.role == Role.USER) "User" else "Assistant"
            "$who: ${turn.content}"
        }
        return "Conversation so far:\n$transcript\n\nUser: $message"
    }

    private fun systemPrompt(accountId: Long): String =
        "You are a money-transfer assistant for account $accountId. Use the tools to look up " +
            "contacts and prepare transfers. When a recipient is ambiguous, ask the user to choose; " +
            "never guess."

    private fun resolve(definitions: LLModelDefinitions, modelId: String): LLModel =
        definitions.modelsById()[modelId]
            ?: error("Unknown Koog model id '$modelId'. Known ids: ${definitions.modelsById().keys.sorted()}")
}
