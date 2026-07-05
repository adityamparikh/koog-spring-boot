package dev.aparikh.moneytransfer.agent.hitl

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory stand-in for the Spring Data JDBC [PendingInteractionRepository], so the confirm-gate
 * logic can be unit-tested without Postgres. Mirrors the atomic remove-and-return semantics of the
 * real `DELETE … RETURNING` [consume]; the Postgres-backed behaviour is covered by [AgentPersistenceIT].
 */
internal class InMemoryPendingInteractionRepository : PendingInteractionRepository {
    private val rows = ConcurrentHashMap<UUID, String>()
    override fun upsert(id: UUID, payload: String) { rows[id] = payload }
    override fun findPayload(id: UUID): String? = rows[id]
    override fun deleteByConversationId(id: UUID) { rows.remove(id) }
    override fun consume(id: UUID): String? = rows.remove(id)
    override fun deleteOlderThan(cutoff: Instant): Int = 0
}
