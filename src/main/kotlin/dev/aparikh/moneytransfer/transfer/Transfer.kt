package dev.aparikh.moneytransfer.transfer

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * Lifecycle of a ledger row (step 7 — async settlement). [PENDING] is the only non-terminal
 * state: the sender has been debited (funds reserved) but the recipient not yet credited.
 * Every transition out of [PENDING] is a conditional `UPDATE … WHERE status = 'PENDING'`, so
 * racing writers (cancel vs. settle) resolve to exactly one winner.
 * - [SETTLED] — recipient credited; terminal and irreversible by design.
 * - [CANCELLED] — sender undid it during the settlement window; sender re-credited.
 * - [FAILED] — settlement impossible (recipient gone); sender re-credited.
 */
enum class TransferStatus { PENDING, SETTLED, CANCELLED, FAILED }

/**
 * An immutable ledger row recording money moving from [senderAccountId] to [recipientAccountId].
 * [settleAt] is when a [TransferStatus.PENDING] row becomes due for settlement — it doubles as
 * the end of the undo window; null only on pre-step-7 historical rows.
 */
@Table("transfer")
data class Transfer(
    @Id
    val id: Long? = null,
    val senderAccountId: Long,
    val recipientAccountId: Long,
    val amount: BigDecimal,
    val currency: String = "USD",
    val purpose: String? = null,
    val status: TransferStatus = TransferStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val settleAt: Instant? = null,
)
