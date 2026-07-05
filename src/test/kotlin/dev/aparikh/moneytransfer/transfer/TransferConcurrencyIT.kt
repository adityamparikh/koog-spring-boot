package dev.aparikh.moneytransfer.transfer

import dev.aparikh.moneytransfer.account.AccountRepository
import dev.aparikh.moneytransfer.common.InsufficientFundsException
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves the atomic conditional debit is concurrency-safe: many simultaneous transfers from one
 * account whose total exceeds the balance must neither lose updates nor overdraw. Requires a real
 * PostgreSQL (Testcontainers); skipped where Docker is unavailable.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class TransferConcurrencyIT {

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

    @Test
    fun `concurrent transfers never overdraw and never lose updates`() {
        val amount = BigDecimal("100.00")
        val startSender = accounts.findById(1).get().balance // 1000.00 (seed)
        val startRecipient = accounts.findById(2).get().balance // 500.00 (seed)
        val threadCount = 20

        val pool = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val successes = AtomicInteger(0)

        val futures = (1..threadCount).map {
            pool.submit {
                startGate.await()
                try {
                    transferService.initiate(1, 2, amount, "concurrent")
                    successes.incrementAndGet()
                } catch (_: InsufficientFundsException) {
                    // Expected once the sender's funds are exhausted.
                }
            }
        }
        startGate.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        val expectedSuccesses = startSender.divideToIntegralValue(amount).toInt() // 10
        val moved = amount.multiply(BigDecimal(successes.get()))
        val senderBalance = accounts.findById(1).get().balance

        assertEquals(expectedSuccesses, successes.get(), "exactly the affordable number of transfers should succeed")
        assertTrue(senderBalance.signum() >= 0, "sender must never go negative")
        assertEquals(0, senderBalance.compareTo(startSender.subtract(moved)), "no lost updates on the sender")
        // Step 7: initiate only reserves — the recipient is untouched until settlement.
        assertEquals(0, accounts.findById(2).get().balance.compareTo(startRecipient), "recipient untouched while PENDING")

        // Settle everything and prove no credit was lost either.
        transfers.findBySenderAccountIdOrderByCreatedAtDesc(1)
            .filter { it.status == TransferStatus.PENDING }
            .forEach { transferService.settle(requireNotNull(it.id)) }
        assertEquals(0, accounts.findById(2).get().balance.compareTo(startRecipient.add(moved)), "no lost updates on the recipient")
    }
}
