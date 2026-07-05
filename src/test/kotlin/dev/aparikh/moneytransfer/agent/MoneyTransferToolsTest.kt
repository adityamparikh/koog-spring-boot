package dev.aparikh.moneytransfer.agent

import dev.aparikh.moneytransfer.account.Account
import dev.aparikh.moneytransfer.account.AccountService
import dev.aparikh.moneytransfer.contact.ContactService
import dev.aparikh.moneytransfer.contact.ResolvedContact
import dev.aparikh.moneytransfer.transfer.Transfer
import dev.aparikh.moneytransfer.transfer.TransferService
import dev.aparikh.moneytransfer.transfer.TransferStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Direct unit tests for the [MoneyTransferTools] `ToolSet` — the tool methods are plain Kotlin
 * functions, so we exercise them without any LLM/agent. Covers delegation, ambiguous-recipient
 * clarification (AC-12), the confirm-gate (AC-13), `getBalance` (AC-15), and cap-and-offer (AC-16).
 */
class MoneyTransferToolsTest {

    private val contactService = mockk<ContactService>()
    private val accountService = mockk<AccountService>()
    private val transferService = mockk<TransferService>()
    private val pending = PendingInteractionStore(InMemoryPendingInteractionRepository())
    private val conversationId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val tools = MoneyTransferTools(
        accountId = 1, conversationId = conversationId,
        contactService = contactService, accountService = accountService,
        transferService = transferService, pending = pending,
    )

    private fun contact(contactId: Long, accountId: Long, name: String, phone: String? = null) =
        ResolvedContact(contactId = contactId, contactAccountId = accountId, displayName = name, nickname = null, phoneNumber = phone)

    @Test
    fun `getContacts lists contacts with ids`() {
        every { contactService.getContacts(1) } returns listOf(
            contact(10, 2, "Alice Smith", "+3510"),
            contact(11, 3, "Bob Johnson"),
        )

        val out = tools.getContacts()

        assertTrue(out.contains("10 | Alice Smith"))
        assertTrue(out.contains("11 | Bob Johnson"))
    }

    @Test
    fun `chooseRecipient records a clarification when the name is ambiguous`() {
        every { contactService.findByName(1, "Daniel") } returns listOf(
            contact(14, 5, "Daniel Anderson"),
            contact(15, 6, "Daniel Craig"),
        )

        val out = tools.chooseRecipient("Daniel")

        assertTrue(out.contains("Daniel Anderson") && out.contains("Daniel Craig"))
        val clarification = pending.get(conversationId)
        assertTrue(clarification is PendingInteraction.Clarification)
        assertEquals(listOf(14L, 15L), (clarification as PendingInteraction.Clarification).candidates.map { it.contactId })
    }

    @Test
    fun `chooseRecipient returns the single match and leaves nothing pending`() {
        every { contactService.findByName(1, "Alice") } returns listOf(contact(10, 2, "Alice Smith"))

        val out = tools.chooseRecipient("Alice")

        assertTrue(out.contains("10"))
        assertNull(pending.get(conversationId))
    }

    @Test
    fun `getBalance returns the account balance`() {
        every { accountService.getBalance(1) } returns BigDecimal("1000.00")

        assertTrue(tools.getBalance().contains("1000.00"))
    }

    @Test
    fun `prepareTransfer stages a confirmation and does NOT transfer`() {
        every { contactService.getContact(1, 10) } returns contact(10, 2, "Alice Smith")
        every { accountService.getBalance(1) } returns BigDecimal("1000.00")

        val out = tools.prepareTransfer(recipientContactId = 10, amount = "50.00", purpose = "lunch")

        assertTrue(out.contains("confirm", ignoreCase = true))
        val confirmation = pending.get(conversationId)
        assertTrue(confirmation is PendingInteraction.Confirmation)
        val staged = (confirmation as PendingInteraction.Confirmation).staged
        assertEquals(2L, staged.recipientAccountId)
        assertEquals(0, staged.amount.compareTo(BigDecimal("50.00")))
        // Advisory balance hint (FR-22): the summary shows what the transfer leaves the user with.
        assertEquals(0, staged.balanceAfter!!.compareTo(BigDecimal("950.00")))
        assertTrue(staged.summary.contains("balance after: $950.00"))
        // Note: the tools call only read methods on TransferService; every money movement
        // (initiate AND cancel) happens app-side in AgentService.reply after the confirm gate.
    }

    @Test
    fun `prepareTransfer over-balance asks the user for a smaller amount and stages nothing`() {
        every { contactService.getContact(1, 10) } returns contact(10, 2, "Alice Smith")
        every { accountService.getBalance(1) } returns BigDecimal("30.00")

        val out = tools.prepareTransfer(recipientContactId = 10, amount = "50.00", purpose = "lunch")

        assertTrue(out.contains("30.00"), "should mention the available balance")
        assertTrue(out.contains("50.00"), "should mention the requested amount")
        assertTrue(out.contains("how much", ignoreCase = true), "should ask the user to choose an amount")
        assertNull(pending.get(conversationId), "nothing staged — the user decides the amount next turn")
    }

    @Test
    fun `prepareTransfer rejects a non-numeric amount without staging`() {
        val out = tools.prepareTransfer(recipientContactId = 10, amount = "a lot", purpose = null)

        assertTrue(out.contains("not a valid amount"))
        assertNull(pending.get(conversationId))
    }

    @Test
    fun `prepareTransfer reports an unknown contactId as a tool result instead of failing the run`() {
        every { contactService.getContact(1, 99) } throws dev.aparikh.moneytransfer.common.UnknownContactException(99)

        val out = tools.prepareTransfer(recipientContactId = 99, amount = "50.00", purpose = null)

        assertTrue(out.contains("99"), "should name the bad contactId")
        assertTrue(out.contains("getContacts"), "should steer the model to a valid lookup")
        assertNull(pending.get(conversationId), "nothing staged for a nonexistent contact")
    }

    // --- step 7: settlement status + undo tools ---

    private fun transfer(id: Long, sender: Long, recipient: Long, status: TransferStatus) = Transfer(
        id = id, senderAccountId = sender, recipientAccountId = recipient,
        amount = BigDecimal("50.00"), purpose = "dinner", status = status, settleAt = Instant.now(),
    )

    @Test
    fun `getRecentTransfers reports direction, status, and settle time`() {
        every { transferService.recentTransfersFor(1, 10) } returns listOf(
            transfer(42, sender = 1, recipient = 5, status = TransferStatus.PENDING),
            transfer(41, sender = 3, recipient = 1, status = TransferStatus.SETTLED),
        )

        val out = tools.getRecentTransfers()

        assertTrue(out.contains("42 | sent to account 5"), "outgoing transfer should read as sent")
        assertTrue(out.contains("PENDING"))
        assertTrue(out.contains("41 | received from account 3"), "incoming transfer should read as received")
        assertTrue(out.contains("SETTLED"))
    }

    @Test
    fun `undoLastTransfer stages a cancel confirmation and does NOT cancel`() {
        every { transferService.latestPendingFor(1) } returns transfer(42, sender = 1, recipient = 5, status = TransferStatus.PENDING)
        every { accountService.getAccount(5) } returns Account(id = 5, firstName = "Daniel", balance = BigDecimal("500.00"), lastName = "Craig")

        val out = tools.undoLastTransfer()

        assertTrue(out.contains("confirm", ignoreCase = true))
        val staged = pending.get(conversationId)
        assertTrue(staged is PendingInteraction.CancelConfirmation)
        staged as PendingInteraction.CancelConfirmation
        assertEquals(42L, staged.transferId)
        assertTrue(staged.summary.contains("Daniel Craig"), "the summary should name the recipient")
        // Nothing is cancelled here — TransferService.cancel runs app-side after the user's "yes".
    }

    @Test
    fun `undoLastTransfer with nothing pending explains that settled transfers are final`() {
        every { transferService.latestPendingFor(1) } returns null

        val out = tools.undoLastTransfer()

        assertTrue(out.contains("final", ignoreCase = true))
        assertNull(pending.get(conversationId))
    }
}
