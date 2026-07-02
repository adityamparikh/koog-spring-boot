package dev.aparikh.moneytransfer.agent

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
}
