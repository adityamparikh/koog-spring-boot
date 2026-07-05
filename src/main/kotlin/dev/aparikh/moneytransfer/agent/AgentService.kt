package dev.aparikh.moneytransfer.agent

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.modelsById
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import dev.aparikh.moneytransfer.common.AgentUnavailableException
import dev.aparikh.moneytransfer.common.InsufficientFundsException
import dev.aparikh.moneytransfer.common.NoPendingInteractionException
import dev.aparikh.moneytransfer.common.TransferNotCancellableException
import dev.aparikh.moneytransfer.common.UnknownAccountException
import dev.aparikh.moneytransfer.common.UnknownTransferException
import dev.aparikh.moneytransfer.account.AccountService
import dev.aparikh.moneytransfer.contact.ContactService
import dev.aparikh.moneytransfer.transfer.TransferProperties
import dev.aparikh.moneytransfer.transfer.TransferService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

/** The kind of turn the API is reporting back to the caller. */
enum class InteractionType { ANSWER, CLARIFICATION, CONFIRMATION }

/** The [InteractionType] a pending item asks the client to answer — the single mapping used by `tag`/`status`. */
private val PendingInteraction.awaiting: InteractionType
    get() = when (this) {
        is PendingInteraction.Clarification -> InteractionType.CLARIFICATION
        is PendingInteraction.Confirmation -> InteractionType.CONFIRMATION
        is PendingInteraction.CancelConfirmation -> InteractionType.CONFIRMATION
    }

/** What a CONFIRMATION is about — send or cancel — for the response's `transferSummary` field. */
private val PendingInteraction.confirmationSummary: String?
    get() = when (this) {
        is PendingInteraction.Confirmation -> staged.summary
        is PendingInteraction.CancelConfirmation -> summary
        is PendingInteraction.Clarification -> null
    }

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
 * **Durable state (step 5) uses Koog's built-in constructs**, keyed by `conversationId` (passed as
 * each run's `sessionId`):
 *  - `ChatMemory` (over [chatHistory], a Postgres provider) owns the conversation transcript — it
 *    loads prior turns into the prompt at run start and stores them at completion, replacing the
 *    old hand-rolled transcript replay.
 *  - `Persistence` (over [checkpointStorage], a Postgres provider) checkpoints each run for
 *    intra-run crash recovery. Completed runs tombstone, so a *finished* turn is never replayed —
 *    cross-turn continuity is ChatMemory's job, not Persistence's.
 *
 * HITL stays app-side because Koog has no cross-turn "pending decision" construct: `prepareTransfer`
 * only *stages* a transfer into the (now Postgres-backed) [PendingInteractionStore], and `reply(...)`
 * executes it deterministically once the user affirms — so no money moves without an explicit "yes",
 * and a paused confirmation survives a restart (AC-18).
 *
 * Threading note: the `suspend` methods here call blocking JDBC (the stores, `TransferService`,
 * `ChatHistoryProvider`) directly rather than wrapping each in `withContext(Dispatchers.IO)`. That is
 * deliberate — the app runs Spring MVC with virtual threads enabled (`spring.threads.virtual.enabled`),
 * so a blocked carrier is cheap; the coroutine boundary exists only because Koog's `agent.run` is
 * `suspend`, not because we need non-blocking I/O.
 */
@Service
class AgentService(
    @param:Qualifier("multiLLMPromptExecutor") private val multiLLMPromptExecutor: PromptExecutor,
    private val chatHistory: ChatHistoryProvider,
    private val checkpointStorage: PersistenceStorageProvider<*>,
    private val pending: PendingInteractionStore,
    private val contactService: ContactService,
    private val accountService: AccountService,
    private val transferService: TransferService,
    private val transferProperties: TransferProperties,
    private val affirmationInterpreter: AffirmationInterpreter,
    private val observability: ObservabilityProperties,
    // Present only when app.observability.enabled=true (the beans are @ConditionalOnProperty); null
    // otherwise, so with observability off the OpenTelemetry feature is simply never installed.
    private val spanExporter: SpanExporter? = null,
    private val metricExporter: MetricExporter? = null,
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
     *
     * The prior transcript is no longer rendered into [message] by hand — `ChatMemory` loads it from
     * Postgres and injects it into the prompt, keyed by the `conversationId` we pass as the run's
     * `sessionId`, and stores the updated transcript when the run completes.
     */
    suspend fun chat(accountId: Long, message: String, conversationId: UUID?): ChatResponse {
        val id = conversationId ?: UUID.randomUUID()

        // Resolve the balance hint (FR-22) BEFORE the fallback loop: an unknown account must
        // surface as 404 here — inside the loop its exception would be mistaken for a provider
        // failure, burn a pointless cross-provider retry, and return a misleading 503.
        val balance = accountService.getBalance(accountId)

        var lastError: Exception? = null
        for ((index, model) in llms.withIndex()) {
            // Clear before *each* attempt, not once before the loop: a new intent discards any stale
            // "yes", and — crucially — a fallback retry must not inherit a confirmation that a prior
            // attempt staged and then abandoned by failing mid-run.
            pending.clear(id)
            try {
                val reply = runAgent(accountId, id, model, message, balance)
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
     * re-running the agent with [answer] as the next message (`ChatMemory` supplies the prior turns,
     * so the LLM re-derives the amount from the transcript). A pending **confirmation** is resolved
     * **app-side**: affirm → execute the staged transfer, deny → discard, unclear → re-prompt.
     *
     * @throws NoPendingInteractionException if nothing is awaiting a reply.
     */
    suspend fun reply(accountId: Long, conversationId: UUID, answer: String): ChatResponse =
        when (val interaction = pending.get(conversationId)) {
            is PendingInteraction.Clarification -> chat(accountId, answer, conversationId)
            is PendingInteraction.Confirmation -> resolveConfirmation(conversationId, interaction, answer)
            is PendingInteraction.CancelConfirmation -> resolveCancelConfirmation(accountId, conversationId, interaction, answer)
            null -> throw NoPendingInteractionException(conversationId)
        }

    /** A read-only snapshot of a conversation for the status endpoint (FR-14). */
    suspend fun status(conversationId: UUID): ConversationStatusResponse {
        val turns = chatHistory.load(conversationId.toString()).count { it is Message.User }
        return ConversationStatusResponse(conversationId, turns, pending.get(conversationId)?.awaiting)
    }

    private suspend fun resolveConfirmation(
        conversationId: UUID,
        confirmation: PendingInteraction.Confirmation,
        answer: String,
    ): ChatResponse {
        val staged = confirmation.staged
        return when (affirmationInterpreter.interpret(answer)) {
            Affirmation.AFFIRM -> {
                // Consume = atomically remove-and-return the staged transfer. Doing both here gives us:
                //  • idempotency — if two "yes" replies race, only one DELETE…RETURNING sees the row,
                //    so the transfer fires at most once (FR-14); the loser gets null below.
                //  • correctness — we execute exactly what we claimed here, not the snapshot read at the
                //    top of reply() (which a concurrent /chat could have re-staged in between).
                val claimed = pending.consume(conversationId) as? PendingInteraction.Confirmation
                if (claimed == null) {
                    return respond(conversationId, answer, "That transfer was already handled, so nothing else was sent.")
                }
                val target = claimed.staged
                // Deliberate ordering: consume() commits before initiate() (reply is not @Transactional),
                // so a crash between them loses the staged intent — no money moved, never a double-send.
                // Do NOT reorder initiate before consume; that trades the safe failure for a double-send.
                val reply = try {
                    // Step 7: initiate debits the sender and queues the transfer as PENDING — the
                    // recipient is credited by the settler after the settlement window (FR-20).
                    transferService.initiate(target.senderAccountId, target.recipientAccountId, target.amount, target.purpose)
                    "Queued — $${target.amount.toPlainString()} to ${target.recipientDisplay} settles in " +
                        "$settlementWindowPhrase; say \"undo\" before then to cancel."
                } catch (e: InsufficientFundsException) {
                    // Step 3 fails honestly here; step 4 adds the "offer up to your balance" flow.
                    "That transfer would exceed your balance, so nothing was sent."
                } catch (e: UnknownAccountException) {
                    "That account no longer exists, so nothing was sent."
                }
                respond(conversationId, answer, reply)
            }

            Affirmation.DENY -> {
                pending.clear(conversationId)
                respond(conversationId, answer, "Okay, I've cancelled that transfer. Nothing was sent.")
            }

            Affirmation.UNCLEAR ->
                respond(
                    conversationId,
                    answer,
                    "Sorry, I didn't catch that. Reply \"yes\" to ${staged.summary}, or \"no\" to cancel.",
                    type = InteractionType.CONFIRMATION,
                    transferSummary = staged.summary,
                )
        }
    }

    /**
     * Resolves a pending **cancellation** (step 7 undo) — the same deterministic gate as sends,
     * in the opposite direction. Affirm → atomically consume, then cancel the PENDING transfer;
     * if the settler won the race in the meantime, report honestly that it already settled.
     */
    private suspend fun resolveCancelConfirmation(
        accountId: Long,
        conversationId: UUID,
        confirmation: PendingInteraction.CancelConfirmation,
        answer: String,
    ): ChatResponse = when (affirmationInterpreter.interpret(answer)) {
        Affirmation.AFFIRM -> {
            // Same consume-first idempotency as sends: racing "yes" replies cancel at most once.
            val claimed = pending.consume(conversationId) as? PendingInteraction.CancelConfirmation
            if (claimed == null) {
                respond(conversationId, answer, "That cancellation was already handled, so nothing else was changed.")
            } else {
                val reply = try {
                    transferService.cancel(claimed.transferId, accountId)
                    "Done — cancelled the transfer; the money is back in your balance."
                } catch (e: TransferNotCancellableException) {
                    "Too late — that transfer already settled, and settled transfers can't be reversed."
                } catch (e: UnknownTransferException) {
                    "That transfer no longer exists, so there was nothing to cancel."
                }
                respond(conversationId, answer, reply)
            }
        }

        Affirmation.DENY -> {
            pending.clear(conversationId)
            respond(conversationId, answer, "Okay — the transfer stays queued and will settle as planned.")
        }

        Affirmation.UNCLEAR ->
            respond(
                conversationId,
                answer,
                "Sorry, I didn't catch that. Reply \"yes\" to ${confirmation.summary.replaceFirstChar { it.lowercase() }}, or \"no\" to keep it.",
                type = InteractionType.CONFIRMATION,
                transferSummary = confirmation.summary,
            )
    }

    /** Records the [userMessage]/[reply] exchange into ChatMemory, then builds the tagged response. */
    private suspend fun respond(
        conversationId: UUID,
        userMessage: String,
        reply: String,
        type: InteractionType = InteractionType.ANSWER,
        transferSummary: String? = null,
    ): ChatResponse {
        record(conversationId, userMessage, reply)
        return ChatResponse(reply, conversationId, type, transferSummary = transferSummary)
    }

    /**
     * Builds a per-request tool-enabled [AIAgent] for [model] and runs one turn, returning the
     * assistant's final text.
     *
     * This is the densest cluster of Koog concepts in the app; each call site is annotated below.
     */
    private suspend fun runAgent(accountId: Long, conversationId: UUID, model: LLModel, input: String, balance: BigDecimal): String {
        // 1. Our ToolSet. Built fresh per request so the acting accountId/conversationId are
        //    captured as fields — never LLM-supplied arguments (that would be an injection risk).
        val tools = MoneyTransferTools(accountId, conversationId, contactService, accountService, transferService, pending)

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
            systemPrompt = systemPrompt(accountId, balance),
        ) {
            // 4. Koog event handlers (FR-09): observe every LLM and tool call as the run unfolds.
            //    Here we just log; the same hooks feed tracing/metrics in step 6. https://docs.koog.ai/agent-events/
            handleEvents {
                onLLMCallStarting { e -> logger.debug("LLM call starting on {} with {} tools", e.model.id, e.tools.size) }
                onLLMCallCompleted { e -> logger.debug("LLM call completed: {}", e.response?.textContent()?.take(200)) }
                onToolCallStarting { e -> logger.info("tool → {} args={}", e.toolName, e.toolArgs) }
                onToolCallCompleted { e -> logger.info("tool ← {} result={}", e.toolName, e.toolResult) }
            }

            // 5. Koog ChatMemory (step 5): the transcript. At run start it loads prior turns for this
            //    sessionId and injects them into the prompt; at completion it stores the updated
            //    transcript back to Postgres. windowSize bounds how many messages are kept.
            //    https://docs.koog.ai/agent-persistency/
            install(ChatMemory) {
                chatHistoryProvider = chatHistory
                windowSize(MEMORY_WINDOW)
            }

            // 6. Koog Persistence (step 5): checkpoints each node for intra-run crash recovery, keyed
            //    by the same sessionId. Completed runs tombstone, so a finished turn is not replayed —
            //    only a run interrupted mid-flight resumes. Money never moves inside the run (tools
            //    only stage), so a resumed run re-stages harmlessly rather than double-sending.
            install(Persistence) {
                storage = checkpointStorage
                enableAutomaticPersistence = true
            }

            // 7. Koog OpenTelemetry (step 6): emits a span per agent run / LLM call / tool call (and,
            //    for a future custom strategy graph, per node/subgraph — the feature hooks the
            //    pipeline, not the strategy) → Tempo, plus GenAI metrics (token usage, latency,
            //    tool-call counts) → Mimir, exported via OTLP to grafana/otel-lgtm. Installed only when
            //    observability is enabled. https://docs.koog.ai/opentelemetry-support/
            //    Do NOT setVerbose(true): this is a money app; verbose emits prompt/response content
            //    (amounts, contact names, balances) unmasked into the spans. Known limitation: Koog
            //    builds an OTel SDK per install and we build an agent per request — see
            //    docs/notes/observability.md ("per-request SDK") for the leak and the step-7 fix.
            if (spanExporter != null || metricExporter != null) {
                install(OpenTelemetry) {
                    setServiceInfo(observability.serviceName, observability.serviceVersion)
                    spanExporter?.let { addSpanExporter(it) }
                    metricExporter?.let { addMetricExporter(it) }
                }
            }
        }

        // 8. Run the turn. The conversationId is the run's sessionId — ChatMemory and Persistence
        //    both scope to it. singleRunStrategy drives LLM↔tool iterations internally; `run`
        //    suspends until the agent produces its final text answer, which we return.
        return agent.run(input, conversationId.toString())
    }

    /** Tags a completed run by inspecting what (if anything) it left pending. */
    private fun tag(reply: String, conversationId: UUID): ChatResponse {
        val p = pending.get(conversationId)
        return ChatResponse(
            reply,
            conversationId,
            type = p?.awaiting ?: InteractionType.ANSWER,
            candidates = (p as? PendingInteraction.Clarification)?.candidates ?: emptyList(),
            transferSummary = p?.confirmationSummary,
        )
    }

    /**
     * Records an app-side exchange (a confirmation resolution) into `ChatMemory` by hand.
     *
     * The confirm-gate resolves **without** running the agent, so those turns never pass through
     * ChatMemory's own store interceptor. We append them to the provider directly so the transcript
     * stays complete for the next `/chat`. (The clarification path runs the agent, so it's stored
     * automatically — only confirmation outcomes need this.)
     *
     * Note: this manual append bypasses ChatMemory's `windowSize` (that's a feature-level pre-processor
     * applied on agent runs, not on direct provider writes). The row is left unbounded here and gets
     * re-trimmed on the next agent run's store; since confirmations are terminal, the drift is at most
     * two messages, so we don't re-implement the windowing (doing so risks dropping the system prompt).
     */
    private suspend fun record(conversationId: UUID, userMessage: String, reply: String) {
        val key = conversationId.toString()
        val history = chatHistory.load(key)
        chatHistory.store(
            key,
            history +
                Message.User(userMessage, RequestMetaInfo.Empty) +
                Message.Assistant(reply, ResponseMetaInfo.Empty),
        )
    }

    /**
     * Built per turn so the balance hint (FR-22) is live, not stale ([balance] is resolved at the
     * top of [chat], outside the fallback loop). The hint is advisory — the tool-level refusal and
     * the domain's atomic debit remain the real guards.
     */
    private fun systemPrompt(accountId: Long, balance: BigDecimal): String =
        "You are a money-transfer assistant for account $accountId. The user's current available " +
            "balance is $${balance.toPlainString()}. Use the tools to " +
            "look up contacts, prepare transfers, check recent transfers, and undo pending ones. " +
            "When a recipient is ambiguous, ask the user to choose; never guess. Money never moves " +
            "— in either direction — without the user's explicit confirmation."

    /** "about 2 minutes" / "about 30 seconds" — how long a queued transfer stays undoable. */
    private val settlementWindowPhrase: String = transferProperties.settlementDelay.let { delay ->
        if (delay.toMinutes() >= 1) {
            "about ${delay.toMinutes()} minute${if (delay.toMinutes() == 1L) "" else "s"}"
        } else {
            "about ${delay.seconds} seconds"
        }
    }

    private fun resolve(definitions: LLModelDefinitions, modelId: String): LLModel =
        definitions.modelsById()[modelId]
            ?: error("Unknown Koog model id '$modelId'. Known ids: ${definitions.modelsById().keys.sorted()}")

    private companion object {
        /** Sliding window of messages ChatMemory keeps per conversation. */
        const val MEMORY_WINDOW = 50
    }
}
