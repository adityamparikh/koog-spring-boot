package dev.aparikh.moneytransfer.transfer

import dev.aparikh.moneytransfer.account.AccountRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * End-to-end async-settlement lifecycle against real PostgreSQL and the real `@Scheduled`
 * settler (AC-30, AC-31). The settlement window is shrunk to ~1s so the scheduler actually
 * fires inside the test. Deliberately NOT `@Transactional`: settlement happens on the
 * scheduler's thread in its own transaction, so each test uses its own seed account pair to
 * stay independent.
 */
@SpringBootTest(
    properties = [
        "app.transfer.settlement-delay=PT1S",
        "app.transfer.settlement-poll-ms=250",
    ],
)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SettlementLifecycleIT {

    @Autowired
    lateinit var transferService: TransferService

    @Autowired
    lateinit var transfers: TransferRepository

    @Autowired
    lateinit var accounts: AccountRepository

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    }

    private fun balance(accountId: Long): BigDecimal = accounts.findById(accountId).get().balance

    private fun status(transferId: Long): TransferStatus = transfers.findById(transferId).get().status

    /** Polls until [condition] holds or ~10s elapse — no extra await library for one loop. */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue(condition(), "condition not met within 10s")
    }

    @Test
    fun `a confirmed transfer settles on its own after the settlement delay`() {
        // Seed pair for this test: accounts 3 and 4, 500.00 each.
        val queued = transferService.initiate(3, 4, BigDecimal("50.00"), "ac-30")

        assertEquals(TransferStatus.PENDING, queued.status)
        assertEquals(0, balance(3).compareTo(BigDecimal("450.00")), "sender debited at initiate")
        assertEquals(0, balance(4).compareTo(BigDecimal("500.00")), "recipient untouched while PENDING")

        // The real scheduler must pick it up once settle_at (≈1s) passes.
        awaitUntil { status(requireNotNull(queued.id)) == TransferStatus.SETTLED }
        assertEquals(0, balance(4).compareTo(BigDecimal("550.00")), "recipient credited by the settler")

        // Exercise the agent tool's bounded read against real SQL (OR-clause, ordering, LIMIT binding)
        // from both sides of the transfer — this query has no other real-database coverage.
        assertEquals(queued.id, transferService.recentTransfersFor(3, 10).first().id, "sender sees the settled transfer")
        assertEquals(queued.id, transferService.recentTransfersFor(4, 10).first().id, "recipient sees it too")
    }

    @Test
    fun `racing cancel and settle produce exactly one winner and consistent balances`() {
        // Seed pair for this test: accounts 5 and 6, 500.00 each.
        val queued = transferService.initiate(5, 6, BigDecimal("100.00"), "ac-31")
        val transferId = requireNotNull(queued.id)

        // Fire cancel and settle simultaneously. The conditional UPDATE guarantees one winner;
        // which one wins is timing-dependent and BOTH outcomes are legal — the invariant under
        // test is that money moved exactly once, whichever way.
        val startGate = CountDownLatch(1)
        val cancelThread = thread {
            startGate.await()
            runCatching { transferService.cancel(transferId, actingAccountId = 5) } // loser throws — fine
        }
        val settleThread = thread {
            startGate.await()
            transferService.settle(transferId) // loser no-ops by design
        }
        startGate.countDown()
        cancelThread.join()
        settleThread.join()

        when (val outcome = status(transferId)) {
            TransferStatus.CANCELLED -> {
                assertEquals(0, balance(5).compareTo(BigDecimal("500.00")), "cancel won: sender refunded")
                assertEquals(0, balance(6).compareTo(BigDecimal("500.00")), "cancel won: recipient untouched")
            }
            TransferStatus.SETTLED -> {
                assertEquals(0, balance(5).compareTo(BigDecimal("400.00")), "settle won: debit stands")
                assertEquals(0, balance(6).compareTo(BigDecimal("600.00")), "settle won: recipient credited")
            }
            else -> throw AssertionError("transfer must end terminal (CANCELLED or SETTLED), was $outcome")
        }
        // Money conservation regardless of winner: the pair started with 1000.00 total.
        assertEquals(0, balance(5).add(balance(6)).compareTo(BigDecimal("1000.00")), "no money created or destroyed")
    }
}
