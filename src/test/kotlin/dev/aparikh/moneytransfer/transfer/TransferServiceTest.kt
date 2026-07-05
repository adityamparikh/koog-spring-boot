package dev.aparikh.moneytransfer.transfer

import dev.aparikh.moneytransfer.account.AccountRepository
import dev.aparikh.moneytransfer.common.InsufficientFundsException
import dev.aparikh.moneytransfer.common.InvalidAmountException
import dev.aparikh.moneytransfer.common.TransferNotCancellableException
import dev.aparikh.moneytransfer.common.UnknownAccountException
import dev.aparikh.moneytransfer.common.UnknownTransferException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/**
 * Unit tests for the [TransferService] state machine (step 7) — no database, repositories are
 * mocked with MockK. The invariant under test throughout: **money moves only behind a winning
 * (`== 1`) conditional status flip**; a writer that loses the `PENDING` race must not touch
 * balances.
 */
class TransferServiceTest {

    private val accounts = mockk<AccountRepository>()
    private val transfers = mockk<TransferRepository>()
    private val service = TransferService(accounts, transfers, TransferProperties())

    private val amount = BigDecimal("100.00")

    private fun transfer(status: TransferStatus = TransferStatus.PENDING) = Transfer(
        id = 10, senderAccountId = 1, recipientAccountId = 2,
        amount = amount, status = status, settleAt = Instant.now(),
    )

    // --- initiate (L5: atomic re-validate + debit + record) ---

    @Test
    fun `initiate debits the sender and records a PENDING row - the recipient is NOT credited`() {
        every { accounts.existsById(1L) } returns true
        every { accounts.existsById(2L) } returns true
        every { accounts.debit(1L, amount) } returns 1
        every { transfers.save(any()) } answers { firstArg<Transfer>().copy(id = 10) }

        val result = service.initiate(1, 2, amount, "lunch")

        assertEquals(TransferStatus.PENDING, result.status)
        assertNotNull(result.settleAt, "a pending transfer must carry its settlement due time")
        verifyOrder {
            accounts.debit(1L, amount)
            transfers.save(any())
        }
        verify(exactly = 0) { accounts.credit(any(), any()) }
    }

    @Test
    fun `initiate rejects a non-positive amount without touching accounts`() {
        assertThrows<InvalidAmountException> { service.initiate(1, 2, BigDecimal.ZERO, null) }
        verify(exactly = 0) { accounts.debit(any(), any()) }
    }

    @Test
    fun `initiate rejects an unknown sender`() {
        every { accounts.existsById(1L) } returns false
        assertThrows<UnknownAccountException> { service.initiate(1, 2, amount, null) }
        verify(exactly = 0) { accounts.debit(any(), any()) }
    }

    @Test
    fun `initiate rejects an unknown recipient`() {
        every { accounts.existsById(1L) } returns true
        every { accounts.existsById(2L) } returns false
        assertThrows<UnknownAccountException> { service.initiate(1, 2, amount, null) }
        verify(exactly = 0) { accounts.debit(any(), any()) }
    }

    @Test
    fun `initiate with insufficient funds records nothing`() {
        every { accounts.existsById(1L) } returns true
        every { accounts.existsById(2L) } returns true
        every { accounts.debit(1L, amount) } returns 0

        assertThrows<InsufficientFundsException> { service.initiate(1, 2, amount, null) }

        verify(exactly = 0) { transfers.save(any()) }
    }

    // --- settle (L6: credit the recipient — or return the funds) ---

    @Test
    fun `settle credits the recipient only after winning the PENDING flip`() {
        every { transfers.findById(10L) } returns Optional.of(transfer())
        every { accounts.existsById(2L) } returns true
        every { transfers.markSettled(10L) } returns 1
        every { accounts.credit(2L, amount) } returns 1

        service.settle(10)

        verifyOrder {
            transfers.markSettled(10L)
            accounts.credit(2L, amount)
        }
    }

    @Test
    fun `settle that loses the race to a cancel moves no money`() {
        every { transfers.findById(10L) } returns Optional.of(transfer())
        every { accounts.existsById(2L) } returns true
        every { transfers.markSettled(10L) } returns 0 // a concurrent cancel won

        service.settle(10)

        verify(exactly = 0) { accounts.credit(any(), any()) }
    }

    @Test
    fun `settle with a vanished recipient fails the transfer and refunds the sender`() {
        every { transfers.findById(10L) } returns Optional.of(transfer())
        every { accounts.existsById(2L) } returns false
        every { transfers.markFailed(10L) } returns 1
        every { accounts.credit(1L, amount) } returns 1

        service.settle(10)

        verifyOrder {
            transfers.markFailed(10L)
            accounts.credit(1L, amount) // refund goes to the SENDER
        }
        verify(exactly = 0) { transfers.markSettled(any()) }
    }

    @Test
    fun `settle of a missing or already-terminal transfer is a no-op`() {
        every { transfers.findById(10L) } returns Optional.empty()
        service.settle(10)

        every { transfers.findById(10L) } returns Optional.of(transfer(TransferStatus.SETTLED))
        service.settle(10)

        verify(exactly = 0) { accounts.credit(any(), any()) }
        verify(exactly = 0) { transfers.markSettled(any()) }
        verify(exactly = 0) { transfers.markFailed(any()) }
    }

    // --- cancel (L9: the undo window; settled is irreversible) ---

    @Test
    fun `cancel refunds the sender and returns the CANCELLED transfer`() {
        every { transfers.findById(10L) } returns Optional.of(transfer())
        every { transfers.markCancelled(10L) } returns 1
        every { accounts.credit(1L, amount) } returns 1

        val result = service.cancel(10, actingAccountId = 1)

        assertEquals(TransferStatus.CANCELLED, result.status)
        verifyOrder {
            transfers.markCancelled(10L)
            accounts.credit(1L, amount)
        }
    }

    @Test
    fun `cancel of a transfer that already settled is rejected without moving money`() {
        every { transfers.findById(10L) } returns Optional.of(transfer(TransferStatus.SETTLED))
        every { transfers.markCancelled(10L) } returns 0

        assertThrows<TransferNotCancellableException> { service.cancel(10, actingAccountId = 1) }

        verify(exactly = 0) { accounts.credit(any(), any()) }
    }

    @Test
    fun `cancel by anyone but the sender reads as not found`() {
        every { transfers.findById(10L) } returns Optional.of(transfer())

        assertThrows<UnknownTransferException> { service.cancel(10, actingAccountId = 99) }

        verify(exactly = 0) { transfers.markCancelled(any()) }
        verify(exactly = 0) { accounts.credit(any(), any()) }
    }

    @Test
    fun `cancel of an unknown transfer is rejected`() {
        every { transfers.findById(10L) } returns Optional.empty()
        assertThrows<UnknownTransferException> { service.cancel(10, actingAccountId = 1) }
    }
}
