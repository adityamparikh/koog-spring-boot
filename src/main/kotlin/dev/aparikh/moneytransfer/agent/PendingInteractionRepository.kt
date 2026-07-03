package dev.aparikh.moneytransfer.agent

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * The one `pending_interaction` row per conversation. `payload` is the JSON of the sealed
 * [PendingInteraction] (its `kind` discriminator lives inside the JSON via Jackson `@JsonTypeInfo`).
 * Only used as the repository's aggregate type — all access goes through the explicit queries below.
 */
@Table("pending_interaction")
data class PendingInteractionRow(
    @Id val conversationId: UUID,
    val payload: String,
    val updatedAt: Instant,
)

/**
 * Spring Data JDBC access for the app-owned confirm-gate state (step 5).
 *
 * A **narrow** [Repository] (not `CrudRepository`) exposing only the operations [PendingInteractionStore]
 * needs — this keeps the interface trivially fakeable in unit tests. The two load-bearing queries:
 *  - [upsert] — client-assigned UUID key, so a plain `save` can't tell insert from update; an
 *    `INSERT … ON CONFLICT DO UPDATE` gives last-write-wins overwrite in one round trip.
 *  - [consume] — `DELETE … RETURNING` removes and returns the row **atomically**, so if two replies
 *    race, exactly one sees the staged transfer and fires it (FR-14 idempotency).
 */
interface PendingInteractionRepository : Repository<PendingInteractionRow, UUID> {

    @Modifying
    @Query(
        """
        INSERT INTO pending_interaction (conversation_id, payload, updated_at)
        VALUES (:id, :payload, now())
        ON CONFLICT (conversation_id) DO UPDATE
            SET payload = EXCLUDED.payload, updated_at = now()
        """,
    )
    fun upsert(@Param("id") id: UUID, @Param("payload") payload: String)

    @Query("SELECT payload FROM pending_interaction WHERE conversation_id = :id")
    fun findPayload(@Param("id") id: UUID): String?

    @Modifying
    @Query("DELETE FROM pending_interaction WHERE conversation_id = :id")
    fun deleteByConversationId(@Param("id") id: UUID)

    /** Atomic remove-and-return; returns null if nothing was pending (or another caller won the race). */
    @Query("DELETE FROM pending_interaction WHERE conversation_id = :id RETURNING payload")
    fun consume(@Param("id") id: UUID): String?

    @Modifying
    @Query("DELETE FROM pending_interaction WHERE updated_at < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: Instant): Int
}
