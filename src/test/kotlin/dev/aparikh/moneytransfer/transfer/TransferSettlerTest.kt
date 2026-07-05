package dev.aparikh.moneytransfer.transfer

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit tests for the settler's poll loop. The state-transition logic itself lives in
 * [TransferService.settle] (covered by [TransferServiceTest]); here we verify the loop's two
 * jobs: settle every due row, and isolate failures so one poisoned row can't starve the rest.
 */
class TransferSettlerTest {

    private val transfers = mockk<TransferRepository>()
    private val service = mockk<TransferService>()
    private val settler = TransferSettler(transfers, service)

    private fun due(id: Long) = Transfer(
        id = id, senderAccountId = 1, recipientAccountId = 2,
        amount = BigDecimal("10.00"), status = TransferStatus.PENDING, settleAt = Instant.now(),
    )

    @Test
    fun `settles every due transfer`() {
        every { transfers.findDueForSettlement(any()) } returns listOf(due(1), due(2))
        every { service.settle(any()) } returns Unit

        settler.settleDueTransfers()

        verify(exactly = 1) { service.settle(1) }
        verify(exactly = 1) { service.settle(2) }
    }

    @Test
    fun `one failing settlement does not stop the others`() {
        every { transfers.findDueForSettlement(any()) } returns listOf(due(1), due(2), due(3))
        every { service.settle(1) } returns Unit
        every { service.settle(2) } throws IllegalStateException("poisoned row")
        every { service.settle(3) } returns Unit

        settler.settleDueTransfers() // must not throw

        verify(exactly = 1) { service.settle(3) } // the row after the failure still settles
    }
}
