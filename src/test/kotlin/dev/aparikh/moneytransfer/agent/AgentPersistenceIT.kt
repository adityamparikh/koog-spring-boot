package dev.aparikh.moneytransfer.agent

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.features.chathistory.jdbc.PostgresJdbcChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * Proves the step-5 Koog-native persistence against **real PostgreSQL** (Testcontainers + Flyway):
 * the checkpoint/history tables exist (AC-17), Koog's `ChatMemory` transcript survives a fresh
 * provider instance (the "restart" analog), and the app-owned confirm-gate is durable, idempotent
 * under concurrent replies (FR-14), and TTL-evictable (AC-18).
 *
 * Deliberately **not** `@Transactional`: durability means state must be *committed* and readable by a
 * brand-new store/provider instance that holds no in-memory state — the whole point of moving off the
 * old `ConcurrentHashMap`. Each test uses random ids so committed rows don't collide; the container is
 * torn down with the class.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgentPersistenceIT {

    @Autowired lateinit var chatHistory: ChatHistoryProvider
    @Autowired lateinit var pending: PendingInteractionStore
    @Autowired lateinit var pendingRepo: PendingInteractionRepository
    @Autowired lateinit var agentService: AgentService
    @Autowired lateinit var dataSource: DataSource

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
    }

    private fun confirmation(amount: String) = PendingInteraction.Confirmation(
        StagedTransfer(senderAccountId = 1, recipientAccountId = 2, recipientDisplay = "Alice Smith", amount = BigDecimal(amount), purpose = "lunch"),
    )

    @Test
    fun `Flyway V3 creates the three persistence tables`() {
        val tables = dataSource.connection.use { c ->
            c.metaData.getTables(null, null, null, arrayOf("TABLE")).use { rs ->
                buildSet { while (rs.next()) add(rs.getString("TABLE_NAME").lowercase()) }
            }
        }
        assertTrue(tables.containsAll(setOf("chat_history", "agent_checkpoints", "pending_interaction")), "missing tables; found $tables")
    }

    @Test
    fun `ChatMemory transcript survives a fresh provider instance`() = runBlocking {
        val id = UUID.randomUUID().toString()
        chatHistory.store(
            id,
            listOf(
                Message.User("who are my contacts?", RequestMetaInfo.Empty),
                Message.Assistant("Alice, Bob, two Daniels.", ResponseMetaInfo.Empty),
            ),
        )

        // A brand-new provider over the same DataSource == a fresh process after restart.
        val afterRestart: ChatHistoryProvider = PostgresJdbcChatHistoryProvider(dataSource)
        val loaded = afterRestart.load(id)

        assertTrue(loaded.any { it is Message.User && it.textContent() == "who are my contacts?" }, "history not durable: $loaded")
    }

    @Test
    fun `a staged confirmation survives a fresh store instance`() {
        val id = UUID.randomUUID()
        pending.put(id, confirmation("25.00"))

        // A new store holds no in-memory state — it can only see the row because it's in Postgres.
        val afterRestart = PendingInteractionStore(pendingRepo)
        val loaded = afterRestart.get(id)

        assertTrue(loaded is PendingInteraction.Confirmation)
        assertEquals(0, (loaded as PendingInteraction.Confirmation).staged.amount.compareTo(BigDecimal("25.00")))
        pending.clear(id)
    }

    @Test
    fun `concurrent consume yields the staged transfer to exactly one caller`() {
        val id = UUID.randomUUID()
        pending.put(id, confirmation("40.00"))

        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit(Callable { pending.consume(id) }) }
            val winners = futures.map { it.get() }.count { it != null }
            assertEquals(1, winners, "exactly one racing consume must win")
        } finally {
            pool.shutdown()
        }
        assertNull(pending.get(id))
    }

    @Test
    fun `deleteOlderThan evicts only rows older than the cutoff`() {
        val id = UUID.randomUUID()
        pending.put(id, confirmation("10.00"))

        // Past cutoff → the just-inserted row is newer, so it survives (regardless of other rows).
        pending.deleteOlderThan(Instant.now().minusSeconds(3_600))
        assertNotNull(pending.get(id), "a fresh row must not be evicted by a past cutoff")

        // Future cutoff → the row now counts as older than the cutoff and is evicted.
        val removed = pending.deleteOlderThan(Instant.now().plusSeconds(60))
        assertTrue(removed >= 1, "expected at least the seeded row to be evicted")
        assertNull(pending.get(id))
    }

    @Test
    fun `resolving a confirmation records the outcome into ChatMemory`() = runBlocking {
        val id = UUID.randomUUID()
        pending.put(id, confirmation("15.00"))

        // Deny → no money moves, but the exchange is resolved app-side (no agent run), so `record`
        // is the only thing that can put it in the transcript. Prove it lands in ChatMemory.
        agentService.reply(accountId = 1, conversationId = id, answer = "no, cancel that")

        val transcript = chatHistory.load(id.toString())
        assertTrue(transcript.any { it is Message.User && it.textContent().contains("cancel") }, "user turn missing: $transcript")
        assertTrue(transcript.any { it is Message.Assistant && it.textContent().contains("cancelled") }, "assistant turn missing: $transcript")
    }
}
