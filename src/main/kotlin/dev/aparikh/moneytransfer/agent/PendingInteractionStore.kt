package dev.aparikh.moneytransfer.agent

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A candidate contact offered to the user during recipient disambiguation. */
data class ContactCandidate(
    val contactId: Long,
    val displayName: String,
    val phoneNumber: String?,
)

/**
 * A transfer that has been prepared by the `prepareTransfer` tool but **not executed** — it waits for
 * an explicit user confirmation. Keeping it here (not in the ledger) is what guarantees no money
 * moves without a "yes".
 */
data class StagedTransfer(
    val senderAccountId: Long,
    val recipientAccountId: Long,
    val recipientDisplay: String,
    val amount: BigDecimal,
    val purpose: String?,
) {
    /** Human-readable one-liner used in the CONFIRMATION prompt. */
    val summary: String
        get() = buildString {
            append("Send $")
            append(amount.toPlainString())
            append(" to ")
            append(recipientDisplay)
            purpose?.takeIf { it.isNotBlank() }?.let { append(" for \"$it\"") }
        }
}

/** What a conversation is currently waiting for the user to answer. */
sealed interface PendingInteraction {
    /** The recipient was ambiguous/unknown; the user must pick one of [candidates]. */
    data class Clarification(val name: String, val candidates: List<ContactCandidate>) : PendingInteraction

    /** A transfer is staged and awaits an affirmative reply before it executes. */
    data class Confirmation(val staged: StagedTransfer) : PendingInteraction
}

/**
 * In-memory, per-`conversationId` store of the one thing a conversation is awaiting (a
 * clarification or a confirmation). This is the app-owned HITL state — deliberately **not**
 * Koog's checkpoint/persistence, which isn't needed until step 5 (durable restart-resume).
 * At most one pending interaction per conversation; a new one overwrites (last-write-wins).
 */
@Component
class PendingInteractionStore {

    private val pending = ConcurrentHashMap<UUID, PendingInteraction>()

    fun get(conversationId: UUID): PendingInteraction? = pending[conversationId]

    fun put(conversationId: UUID, interaction: PendingInteraction) {
        pending[conversationId] = interaction
    }

    fun clear(conversationId: UUID) {
        pending.remove(conversationId)
    }
}
