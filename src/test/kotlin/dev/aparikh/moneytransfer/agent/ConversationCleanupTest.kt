package dev.aparikh.moneytransfer.agent

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit test for the TTL sweep: it must ask the store to evict rows older than `now − ttl`, using
 * the configured `conversationTtlSeconds`. The store is mocked so we can capture the cutoff.
 */
class ConversationCleanupTest {

    @Test
    fun `evicts pending interactions older than now minus the configured ttl`() {
        val pending = mockk<PendingInteractionStore>()
        val cutoff = slot<Instant>()
        every { pending.deleteOlderThan(capture(cutoff)) } returns 2

        val ttlSeconds = 3_600L
        val cleanup = ConversationCleanup(pending, AgentModelProperties(conversationTtlSeconds = ttlSeconds))

        val lowerBound = Instant.now().minusSeconds(ttlSeconds).minusSeconds(2) // small clock tolerance
        cleanup.evictAbandonedPending()
        val upperBound = Instant.now().minusSeconds(ttlSeconds).plusSeconds(2)

        verify(exactly = 1) { pending.deleteOlderThan(any()) }
        assertFalse(cutoff.captured.isBefore(lowerBound), "cutoff ${cutoff.captured} earlier than now−ttl")
        assertFalse(cutoff.captured.isAfter(upperBound), "cutoff ${cutoff.captured} later than now−ttl")
    }
}
