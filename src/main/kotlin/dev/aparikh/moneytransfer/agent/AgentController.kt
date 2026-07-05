package dev.aparikh.moneytransfer.agent

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** A chat turn. [conversationId] is null on the first turn and echoed back for follow-ups. */
data class ChatRequest(
    val message: String,
    val conversationId: UUID? = null,
)

/** An answer to a pending clarification or confirmation for a conversation. */
data class ReplyRequest(
    val answer: String,
)

/**
 * How the conversation's most recent **agent run** ended, derived from Koog's `Persistence`
 * checkpoints (the workshop-style tombstone probe): on clean completion Koog writes a terminal
 * "tombstone" checkpoint, so the latest checkpoint's shape tells the story without any extra
 * bookkeeping.
 * - [NONE] — no checkpoints: no agent run yet (reply-only turns don't run the agent).
 * - [COMPLETED] — the latest checkpoint is the tombstone: the last run finished cleanly.
 * - [INTERRUPTED] — a mid-run checkpoint is the latest: the last run died in flight (crash,
 *   provider failure on the final fallback) and never tombstoned.
 */
enum class LastRunState { NONE, COMPLETED, INTERRUPTED }

/**
 * A read-only snapshot of a conversation's durable state (FR-14). [turns] is the number of user
 * messages held in `ChatMemory`; [awaiting] is what the conversation is currently paused on
 * (`CLARIFICATION`/`CONFIRMATION`), or null if nothing is pending; [lastRun] is how the most
 * recent agent run ended, from the checkpoint tombstone.
 */
data class ConversationStatusResponse(
    val conversationId: UUID,
    val turns: Int,
    val awaiting: InteractionType?,
    val lastRun: LastRunState,
)

/**
 * REST surface for the tool-enabled agent (step 3). `chat` starts/continues a conversation;
 * `reply` answers a pending clarification or confirmation. Both take the acting user via
 * `X-User-Id` and return the service's tagged [ChatResponse] as-is.
 */
@RestController
@RequestMapping("/api/v1/agent")
class AgentController(
    private val agentService: AgentService,
) {

    /** Sends one message; may return an `ANSWER`, a `CLARIFICATION`, or a `CONFIRMATION`. */
    @PostMapping("/chat")
    suspend fun chat(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: ChatRequest,
    ): ChatResponse =
        agentService.chat(userId, request.message, request.conversationId)

    /** Answers a pending clarification (pick a contact) or confirmation ("yes"/"no"). */
    @PostMapping("/{conversationId}/reply")
    suspend fun reply(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable conversationId: UUID,
        @RequestBody request: ReplyRequest,
    ): ChatResponse =
        agentService.reply(userId, conversationId, request.answer)

    /** Reports a conversation's durable state: how many turns it holds and what (if anything) it awaits. */
    @GetMapping("/{conversationId}/status")
    suspend fun status(
        @PathVariable conversationId: UUID,
    ): ConversationStatusResponse =
        agentService.status(conversationId)
}
