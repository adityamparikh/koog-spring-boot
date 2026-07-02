package dev.aparikh.moneytransfer.agent

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * A chat turn. [conversationId] is null on the first turn and echoed back for follow-ups.
 */
data class ChatRequest(
    val message: String,
    val conversationId: UUID? = null,
)

/** The agent's reply plus the conversation id to send on the next turn. */
data class ChatResponse(
    val reply: String,
    val conversationId: UUID,
)

/**
 * REST surface for the conversational agent (step 2). No tools yet — a single message in,
 * a text reply out, keyed by a `conversationId` for multi-turn context.
 */
@RestController
@RequestMapping("/api/v1/agent")
class AgentController(
    private val agentService: AgentService,
) {

    /** Sends one message from the acting user (`X-User-Id`) and returns the agent's reply. */
    @PostMapping("/chat")
    fun chat(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: ChatRequest,
    ): ChatResponse {
        val result = agentService.chat(userId, request.message, request.conversationId)
        return ChatResponse(reply = result.reply, conversationId = result.conversationId)
    }
}
