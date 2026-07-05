package dev.aparikh.moneytransfer.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.aparikh.moneytransfer.account.AccountService
import dev.aparikh.moneytransfer.common.UnknownContactException
import dev.aparikh.moneytransfer.contact.ContactService
import dev.aparikh.moneytransfer.transfer.TransferService
import dev.aparikh.moneytransfer.transfer.TransferStatus
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Typed tool results (the workshop's `getOrderHistory(): List<OrderDetails>` style): Koog
 * serializes a `@Tool` method's return value to JSON via kotlinx-serialization, so the model
 * receives structured fields instead of parsing pre-formatted strings.
 *
 * Money and timestamps are pre-rendered [String]s — `BigDecimal`/`java.time.Instant` have no
 * kotlinx serializers, and "50.00" / an explicit-UTC timestamp are what the model should repeat
 * to the user verbatim anyway.
 */

/** A saved contact as the model sees it; [contactId] is what `prepareTransfer` needs. */
@Serializable
data class ContactView(
    val contactId: Long,
    val displayName: String,
    val nickname: String?,
    val phoneNumber: String?,
)

/** The acting user's available balance, pre-rendered ("1000.00"). */
@Serializable
data class BalanceView(
    val availableUsd: String,
)

/** Whether the acting user sent or received a transfer. */
enum class TransferDirection { SENT, RECEIVED }

/** One ledger row as the model sees it, from the acting user's perspective. */
@Serializable
data class TransferView(
    val transferId: Long,
    val direction: TransferDirection,
    @property:LLMDescription("The other account in the transfer: recipient if SENT, sender if RECEIVED")
    val counterpartyAccountId: Long,
    val amountUsd: String,
    val status: TransferStatus,
    @property:LLMDescription("When the transfer settles/settled (UTC) — a PENDING transfer's undo deadline; null on old rows predating async settlement")
    val settlesAtUtc: String?,
    val purpose: String?,
)

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
@LLMDescription("Tools for viewing contacts, preparing money transfers, and undoing pending transfers for the current user.")
class MoneyTransferTools(
    private val accountId: Long,
    private val conversationId: UUID,
    private val contactService: ContactService,
    private val accountService: AccountService,
    private val transferService: TransferService,
    private val pending: PendingInteractionStore,
) : ToolSet {

    // Koog tool anatomy (applies to every method below):
    //  • @Tool marks the method as callable by the LLM (name defaults to the function name).
    //  • @LLMDescription on the method is the tool's description; on each parameter it describes
    //    that argument — together they become the JSON tool schema the model sees.
    //  • The return value is serialized to JSON as the tool result: the read tools return typed
    //    views ([ContactView], [BalanceView], [TransferView]) the model can reason over field by
    //    field; the staging tools return String INSTRUCTIONS ("ask the user to confirm") because
    //    their result is conversational guidance, not data — the same split the workshop uses.
    // https://docs.koog.ai/tools-overview/

    /** Lists the user's contacts, each with the `contactId` needed by [prepareTransfer]. */
    @Tool
    @LLMDescription(
        "List the current user's saved contacts. Returns an empty list if there are none. " +
            "Use the contactId with prepareTransfer.",
    )
    fun getContacts(): List<ContactView> =
        contactService.getContacts(accountId).map {
            ContactView(
                contactId = it.contactId,
                displayName = it.displayName,
                nickname = it.nickname,
                phoneNumber = it.phoneNumber,
            )
        }

    /** The acting user's current available balance (FR-11). */
    @Tool
    @LLMDescription("Get the current user's available balance, in USD.")
    fun getBalance(): BalanceView =
        BalanceView(availableUsd = accountService.getBalance(accountId).toPlainString())

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
     *
     * **Overdraft protection (FR-12):** when the requested amount exceeds the sender's balance,
     * this does **not** stage anything and does **not** silently cap to the balance — it tells
     * the model to ask the user how much they want to send (up to their balance), so the **user's
     * input decides the amount**, not the tool. This is an AI-layer UX nicety, not a safety guard:
     * the domain (`TransferService`) still rejects any overdraw atomically, so a stale/wrong
     * balance read here can never actually overdraw.
     */
    @Tool
    @LLMDescription(
        "Prepare a money transfer to one of the user's contacts. This does NOT send money — it " +
            "stages the transfer and asks the user to confirm. If the amount is more than the " +
            "user's balance, it does NOT stage anything — instead, ask the user how much they'd " +
            "like to send (up to their available balance) and call this tool again with that " +
            "amount. Only call this once you have a concrete recipient contactId (from getContacts " +
            "or chooseRecipient).",
    )
    fun prepareTransfer(
        @LLMDescription("The recipient's contactId") recipientContactId: Long,
        @LLMDescription("The amount to send in USD, e.g. 50 or 50.00") amount: String,
        @LLMDescription("Optional note describing the transfer's purpose") purpose: String? = null,
    ): String {
        val requested = try {
            BigDecimal(amount.trim())
        } catch (_: NumberFormatException) {
            return "\"$amount\" is not a valid amount. Ask the user for a numeric USD amount."
        }
        if (requested.signum() <= 0) return "The amount must be greater than zero."

        // A bad contactId is the model's mistake, not a provider failure: report it as a tool
        // result so the LLM corrects itself, instead of throwing (which would fail the run and
        // burn a pointless cross-provider fallback in AgentService).
        val contact = try {
            contactService.getContact(accountId, recipientContactId)
        } catch (_: UnknownContactException) {
            return "No contact with contactId $recipientContactId exists for this user. " +
                "Use getContacts or chooseRecipient to find the right contactId."
        }
        val available = accountService.getBalance(accountId)

        // Overdraft protection: don't stage an over-balance transfer. Let the USER choose the
        // amount (up to their balance) on the next turn — we don't assume they want it all.
        if (requested > available) {
            return if (available.signum() <= 0) {
                "The user has no available balance ($${available.toPlainString()}), so nothing can be sent right now."
            } else {
                "The user asked to send $${requested.toPlainString()} but only has $${available.toPlainString()} " +
                    "available. Do NOT stage a transfer — ask the user how much they would like to send, up to " +
                    "$${available.toPlainString()}, then call prepareTransfer again with that amount."
            }
        }

        val staged = StagedTransfer(
            senderAccountId = accountId,
            recipientAccountId = contact.contactAccountId,
            recipientDisplay = contact.displayName,
            amount = requested,
            purpose = purpose,
            // Advisory balance hint (FR-22): shown in the confirmation summary so the user sees
            // what the transfer leaves them with. A snapshot, not a guard — the atomic debit at
            // execution time is the only enforcement.
            balanceAfter = available - requested,
        )
        pending.put(conversationId, PendingInteraction.Confirmation(staged))
        return "Ready to ${staged.summary}. Ask the user to confirm before sending."
    }

    /** The user's recent transfers, so the agent can answer "did it go through?" (FR-21). */
    @Tool
    @LLMDescription(
        "List the current user's recent transfers (sent and received), newest first. Returns an " +
            "empty list if there are none. PENDING transfers have not yet reached the recipient " +
            "and can still be undone; SETTLED ones are final.",
    )
    fun getRecentTransfers(): List<TransferView> =
        transferService.recentTransfersFor(accountId, RECENT_TRANSFER_LIMIT).map { t ->
            val sent = t.senderAccountId == accountId
            TransferView(
                transferId = requireNotNull(t.id),
                direction = if (sent) TransferDirection.SENT else TransferDirection.RECEIVED,
                counterpartyAccountId = if (sent) t.recipientAccountId else t.senderAccountId,
                amountUsd = t.amount.toPlainString(),
                status = t.status,
                settlesAtUtc = t.settleAt?.let { TIME_FORMAT.format(it) },
                purpose = t.purpose,
            )
        }

    /**
     * **Stages** a cancellation of the user's most recent PENDING transfer — it does NOT cancel.
     * Like [prepareTransfer], the actual state change happens app-side only after the user
     * confirms (money moving back is still money moving).
     */
    @Tool
    @LLMDescription(
        "Undo the user's most recent pending transfer. This does NOT cancel it — it stages the " +
            "cancellation and asks the user to confirm. Only PENDING transfers (not yet settled) " +
            "can be undone; if there is none, say so and explain that settled transfers are final.",
    )
    fun undoLastTransfer(): String {
        val target = transferService.latestPendingFor(accountId)
            ?: return "There is no pending transfer to undo. Settled transfers are final and cannot be reversed."
        val recipientName = runCatching { accountService.getAccount(target.recipientAccountId) }
            .map { listOfNotNull(it.firstName, it.lastName).joinToString(" ") }
            .getOrDefault("account ${target.recipientAccountId}")
        val summary = "Cancel your $${target.amount.toPlainString()} transfer to $recipientName" +
            (target.purpose?.takeIf { it.isNotBlank() }?.let { " for \"$it\"" } ?: "")
        pending.put(conversationId, PendingInteraction.CancelConfirmation(requireNotNull(target.id), summary))
        return "Ready to ${summary.replaceFirstChar { it.lowercase() }}. Ask the user to confirm before cancelling."
    }

    private companion object {
        const val RECENT_TRANSFER_LIMIT = 10

        /** Compact, unambiguous settle-time rendering for the tool result (UTC). */
        val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneId.of("UTC"))
    }
}
