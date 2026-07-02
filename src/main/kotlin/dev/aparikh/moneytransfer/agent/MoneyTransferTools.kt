package dev.aparikh.moneytransfer.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.aparikh.moneytransfer.contact.ContactService
import java.math.BigDecimal

/**
 * Koog **tools** the agent can call to act on the money-transfer domain.
 *
 * A [ToolSet] is Koog's reflection-based way to expose annotated methods as LLM tools:
 * `@Tool` marks a callable, `@LLMDescription` tells the model what the tool/parameter does, and
 * `asTools()` turns them into `Tool` objects for a `ToolRegistry`.
 * https://docs.koog.ai/tools-overview/
 *
 * **Per-request instance:** the acting [accountId] and [conversationId] are captured as fields,
 * **not** exposed as LLM parameters — letting the model pass an arbitrary sender account would be
 * an injection risk. A fresh instance is built for each turn (see `AgentService`), which is also
 * coroutine-safe (no `ThreadLocal` that a dispatched tool call could lose).
 *
 * The HITL-sensitive tools never act irreversibly: `chooseRecipient` may record a
 * [PendingInteraction.Clarification], and `prepareTransfer` only **stages** a
 * [PendingInteraction.Confirmation] — the transfer itself executes app-side after the user
 * confirms (see `AgentService.reply`).
 */
@LLMDescription("Tools for viewing contacts and preparing money transfers for the current user.")
class MoneyTransferTools(
    private val accountId: Long,
    private val conversationId: java.util.UUID,
    private val contactService: ContactService,
    private val pending: PendingInteractionStore,
) : ToolSet {

    // Koog tool anatomy (applies to all three methods below):
    //  • @Tool marks the method as callable by the LLM (name defaults to the function name).
    //  • @LLMDescription on the method is the tool's description; on each parameter it describes
    //    that argument — together they become the JSON tool schema the model sees.
    //  • The String return value is fed back to the model as the tool result.
    // https://docs.koog.ai/tools-overview/

    /** Lists the user's contacts, each with the `contactId` needed by [prepareTransfer]. */
    @Tool
    @LLMDescription(
        "List the current user's saved contacts. Returns one line per contact as " +
            "'contactId | displayName | phone'. Use the contactId with prepareTransfer.",
    )
    fun getContacts(): String {
        val contacts = contactService.getContacts(accountId)
        if (contacts.isEmpty()) return "You have no saved contacts."
        return contacts.joinToString("\n") { "${it.contactId} | ${it.displayName} | ${it.phoneNumber ?: "—"}" }
    }

    /**
     * Resolves a recipient by name/nickname. One match → returns it; ambiguous or unknown →
     * records a clarification and asks the user to choose.
     */
    @Tool
    @LLMDescription(
        "Resolve a recipient the user named. If exactly one contact matches, returns its " +
            "contactId. If several match (or none), asks the user to clarify which contact they " +
            "mean — do NOT guess; relay the choices and wait for the user.",
    )
    fun chooseRecipient(
        @LLMDescription("The recipient name or nickname the user mentioned") name: String,
    ): String {
        val matches = contactService.findByName(accountId, name)
        return when (matches.size) {
            1 -> {
                pending.clear(conversationId)
                val c = matches.single()
                "Matched contact ${c.contactId}: ${c.displayName}. Use contactId ${c.contactId} with prepareTransfer."
            }

            0 -> {
                pending.clear(conversationId)
                "No contact matches \"$name\". Ask the user to check the name or list their contacts."
            }

            else -> {
                val candidates = matches.map { ContactCandidate(it.contactId, it.displayName, it.phoneNumber) }
                pending.put(conversationId, PendingInteraction.Clarification(name, candidates))
                val listed = candidates.joinToString("; ") { "${it.displayName} (contactId ${it.contactId})" }
                "Multiple contacts match \"$name\": $listed. Ask the user which one they mean."
            }
        }
    }

    /**
     * **Stages** a transfer for confirmation — it does NOT move money. Records a pending
     * confirmation; the user must approve before `AgentService` executes it.
     */
    @Tool
    @LLMDescription(
        "Prepare a money transfer to one of the user's contacts. This does NOT send money — it " +
            "stages the transfer and asks the user to confirm. Only call this once you have a " +
            "concrete recipient contactId (from getContacts or chooseRecipient).",
    )
    fun prepareTransfer(
        @LLMDescription("The recipient's contactId") recipientContactId: Long,
        @LLMDescription("The amount to send in EUR, e.g. 50 or 50.00") amount: String,
        @LLMDescription("Optional note describing the transfer's purpose") purpose: String? = null,
    ): String {
        val parsed = try {
            BigDecimal(amount.trim())
        } catch (_: NumberFormatException) {
            return "\"$amount\" is not a valid amount. Ask the user for a numeric EUR amount."
        }
        if (parsed.signum() <= 0) return "The amount must be greater than zero."

        val contact = contactService.getContact(accountId, recipientContactId)
        val staged = StagedTransfer(
            senderAccountId = accountId,
            recipientAccountId = contact.contactAccountId,
            recipientDisplay = contact.displayName,
            amount = parsed,
            purpose = purpose,
        )
        pending.put(conversationId, PendingInteraction.Confirmation(staged))
        return "Ready to ${staged.summary}. Ask the user to confirm before sending."
    }
}
