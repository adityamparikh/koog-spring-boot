package dev.aparikh.moneytransfer.transfer

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * Spring Data JDBC repository for the [Transfer] ledger — which, from step 7, is also the
 * settlement **work queue**: the settler polls [findDueForSettlement] and the state transitions
 * are atomic conditional UPDATEs ([markSettled]/[markCancelled]/[markFailed]) guarded by
 * `status = 'PENDING'`, so a racing cancel and settle resolve to exactly one winner (the loser's
 * update count is 0 and it must not touch balances).
 */
interface TransferRepository : CrudRepository<Transfer, Long> {

    /** Transfers an account can see — sent **or** received — most recent first. */
    @Query(
        """
        SELECT * FROM transfer
        WHERE sender_account_id = :accountId OR recipient_account_id = :accountId
        ORDER BY created_at DESC
        """,
    )
    fun findAllForAccount(@Param("accountId") accountId: Long): List<Transfer>

    /** Like [findAllForAccount] but bounded — the agent's status tool never needs full history. */
    @Query(
        """
        SELECT * FROM transfer
        WHERE sender_account_id = :accountId OR recipient_account_id = :accountId
        ORDER BY created_at DESC
        LIMIT :limit
        """,
    )
    fun findRecentForAccount(@Param("accountId") accountId: Long, @Param("limit") limit: Int): List<Transfer>

    /** A sender's own transfers, most recent first. */
    fun findBySenderAccountIdOrderByCreatedAtDesc(senderAccountId: Long): List<Transfer>

    /** The sender's newest PENDING transfer — the target of "undo my last transfer". */
    @Query(
        """
        SELECT * FROM transfer
        WHERE sender_account_id = :senderAccountId AND status = 'PENDING'
        ORDER BY created_at DESC
        LIMIT 1
        """,
    )
    fun findLatestPendingFor(@Param("senderAccountId") senderAccountId: Long): Transfer?

    /** The settler's poll: PENDING rows whose settlement time has arrived. */
    @Query(
        """
        SELECT * FROM transfer
        WHERE status = 'PENDING' AND settle_at <= :dueAt
        ORDER BY settle_at
        """,
    )
    fun findDueForSettlement(@Param("dueAt") dueAt: Instant): List<Transfer>

    /** PENDING → SETTLED. Returns 1 only for the winning writer; 0 means the row already left PENDING. */
    @Modifying
    @Query("UPDATE transfer SET status = 'SETTLED' WHERE id = :id AND status = 'PENDING'")
    fun markSettled(@Param("id") id: Long): Int

    /** PENDING → CANCELLED. Returns 1 only for the winning writer; 0 means the row already left PENDING. */
    @Modifying
    @Query("UPDATE transfer SET status = 'CANCELLED' WHERE id = :id AND status = 'PENDING'")
    fun markCancelled(@Param("id") id: Long): Int

    /** PENDING → FAILED. Returns 1 only for the winning writer; 0 means the row already left PENDING. */
    @Modifying
    @Query("UPDATE transfer SET status = 'FAILED' WHERE id = :id AND status = 'PENDING'")
    fun markFailed(@Param("id") id: Long): Int
}
