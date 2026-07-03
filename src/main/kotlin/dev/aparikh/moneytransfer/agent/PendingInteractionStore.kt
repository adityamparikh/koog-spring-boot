package dev.aparikh.moneytransfer.agent

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

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
    /** Human-readable one-liner used in the CONFIRMATION prompt. Derived — never persisted. */
    @get:JsonIgnore
    val summary: String
        get() = buildString {
            append("Send $")
            append(amount.toPlainString())
            append(" to ")
            append(recipientDisplay)
            purpose?.takeIf { it.isNotBlank() }?.let { append(" for \"$it\"") }
        }
}

/**
 * What a conversation is currently waiting for the user to answer.
 *
 * Serialized to the `pending_interaction.payload` column as JSON; the Jackson `@JsonTypeInfo`
 * discriminator (`kind`) makes the sealed hierarchy round-trip polymorphically.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = PendingInteraction.Clarification::class, name = "CLARIFICATION"),
    JsonSubTypes.Type(value = PendingInteraction.Confirmation::class, name = "CONFIRMATION"),
)
sealed interface PendingInteraction {
    /** The recipient was ambiguous/unknown; the user must pick one of [candidates]. */
    data class Clarification(val name: String, val candidates: List<ContactCandidate>) : PendingInteraction

    /** A transfer is staged and awaits an affirmative reply before it executes. */
    data class Confirmation(val staged: StagedTransfer) : PendingInteraction
}

/**
 * Per-`conversationId` store of the one thing a conversation is awaiting (a clarification or a
 * confirmation) — the app-owned HITL state, **persisted to Postgres** (step 5).
 *
 * Koog has no construct for this: `ChatMemory` stores messages, and `Persistence` tombstones
 * completed runs, so a "staged transfer awaiting a yes across turns" has no home in either. Persisting
 * it here is what lets a paused confirmation survive an app restart (AC-18) and stay idempotent under
 * concurrent replies (the atomic [consume]). The public API stays synchronous (blocking JDBC) so the
 * non-suspend `@Tool` methods in [MoneyTransferTools] call `put`/`clear` unchanged.
 */
@Component
class PendingInteractionStore(
    private val repository: PendingInteractionRepository,
    private val objectMapper: ObjectMapper,
) {

    fun get(conversationId: UUID): PendingInteraction? =
        repository.findPayload(conversationId)?.let(::deserialize)

    fun put(conversationId: UUID, interaction: PendingInteraction) {
        repository.upsert(conversationId, objectMapper.writeValueAsString(interaction))
    }

    fun clear(conversationId: UUID) {
        repository.deleteByConversationId(conversationId)
    }

    /**
     * Atomically removes and returns what the conversation was awaiting. Used to resolve a
     * confirmation exactly once: `DELETE … RETURNING` guarantees only one caller of a racing pair
     * gets the staged transfer, so an affirmed transfer can fire at most once.
     */
    fun consume(conversationId: UUID): PendingInteraction? =
        repository.consume(conversationId)?.let(::deserialize)

    /** Evicts abandoned rows older than [cutoff]; returns the number removed. Driven by the TTL sweep. */
    fun deleteOlderThan(cutoff: Instant): Int = repository.deleteOlderThan(cutoff)

    private fun deserialize(json: String): PendingInteraction =
        objectMapper.readValue(json, PendingInteraction::class.java)
}
