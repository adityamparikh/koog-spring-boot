package dev.aparikh.moneytransfer.agent

import dev.aparikh.moneytransfer.account.AccountService
import dev.aparikh.moneytransfer.contact.ContactService
import dev.aparikh.moneytransfer.contact.ResolvedContact
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Direct unit tests for the [MoneyTransferTools] `ToolSet` — the tool methods are plain Kotlin
 * functions, so we exercise them without any LLM/agent. Covers delegation, ambiguous-recipient
 * clarification (AC-12), the confirm-gate (AC-13), `getBalance` (AC-15), and cap-and-offer (AC-16).
 */
class MoneyTransferToolsTest {

    private val contactService = mockk<ContactService>()
    private val accountService = mockk<AccountService>()
    private val pending = PendingInteractionStore(InMemoryPendingInteractionRepository(), com.fasterxml.jackson.module.kotlin.jacksonObjectMapper())
    private val conversationId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val tools = MoneyTransferTools(
        accountId = 1, conversationId = conversationId,
        contactService = contactService, accountService = accountService, pending = pending,
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
        // Note: MoneyTransferTools has no reference to TransferService — it structurally cannot move money.
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
}
