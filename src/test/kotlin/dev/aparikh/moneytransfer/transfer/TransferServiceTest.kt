package dev.aparikh.moneytransfer.transfer

import dev.aparikh.moneytransfer.account.AccountRepository
import dev.aparikh.moneytransfer.common.InsufficientFundsException
import dev.aparikh.moneytransfer.common.InvalidAmountException
import dev.aparikh.moneytransfer.common.UnknownAccountException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/** Unit tests for [TransferService] — no database, repositories are mocked with MockK. */
class TransferServiceTest {

    private val accounts = mockk<AccountRepository>()
    private val transfers = mockk<TransferRepository>()
    private val service = TransferService(accounts, transfers)

    private val amount = BigDecimal("100.00")

    @Test
    fun `transfer debits sender, credits recipient, and records the ledger, in order`() {
        every { accounts.existsById(1L) } returns true
        every { accounts.existsById(2L) } returns true
        every { accounts.debit(1L, amount) } returns 1
        every { accounts.credit(2L, amount) } returns 1
        val saved = Transfer(
            id = 10, senderAccountId = 1, recipientAccountId = 2,
            amount = amount, status = TransferStatus.COMPLETED,
        )
        every { transfers.save(any()) } returns saved

        val result = service.transfer(1, 2, amount, "lunch")

        assertEquals(TransferStatus.COMPLETED, result.status)
        verifyOrder {
            accounts.debit(1L, amount)
            accounts.credit(2L, amount)
            transfers.save(any())
        }
    }

    @Test
    fun `transfer rejects a non-positive amount without touching accounts`() {
        assertThrows<InvalidAmountException> { service.transfer(1, 2, BigDecimal.ZERO, null) }
        verify(exactly = 0) { accounts.debit(any(), any()) }
    }

    @Test
    fun `transfer rejects an unknown sender`() {
        every { accounts.existsById(1L) } returns false
        assertThrows<UnknownAccountException> { service.transfer(1, 2, amount, null) }
        verify(exactly = 0) { accounts.debit(any(), any()) }
    }

    @Test
    fun `transfer rejects an unknown recipient`() {
        every { accounts.existsById(1L) } returns true
        every { accounts.existsById(2L) } returns false
        assertThrows<UnknownAccountException> { service.transfer(1, 2, amount, null) }
        verify(exactly = 0) { accounts.debit(any(), any()) }
    }

    @Test
    fun `transfer with insufficient funds does not credit or record`() {
        every { accounts.existsById(1L) } returns true
        every { accounts.existsById(2L) } returns true
        every { accounts.debit(1L, amount) } returns 0

        assertThrows<InsufficientFundsException> { service.transfer(1, 2, amount, null) }

        verify(exactly = 0) { accounts.credit(any(), any()) }
        verify(exactly = 0) { transfers.save(any()) }
    }
}
