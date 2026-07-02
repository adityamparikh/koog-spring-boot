package dev.aparikh.moneytransfer.agent

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Who produced a conversation [Turn]. */
enum class Role { USER, ASSISTANT }

/** One message in a conversation transcript. */
data class Turn(val role: Role, val content: String)

/**
 * In-memory, per-`conversationId` transcript store.
 *
 * This is the deliberately simple **seam** that later steps replace: Koog has no
 * cross-request session API at this version, so step 2 keeps prior turns here and replays
 * them into each single-run agent call (see [AgentService.renderInput]). Step 3 swaps this
 * for Koog's checkpoint/persistence feature (in-memory provider) to implement pause/resume,
 * and step 5 moves that provider to Postgres — callers of this class do not change, only its
 * implementation does. https://docs.koog.ai/agent-persistency/
 */
@Component
class ConversationStore {

    private val transcripts = ConcurrentHashMap<UUID, List<Turn>>()

    /** Prior turns for [conversationId], oldest first; empty for a new conversation. */
    fun historyOf(conversationId: UUID): List<Turn> = transcripts[conversationId] ?: emptyList()

    /** Appends [turns] to [conversationId]'s transcript atomically. */
    fun append(conversationId: UUID, vararg turns: Turn) {
        transcripts.merge(conversationId, turns.toList()) { existing, added -> existing + added }
    }
}
