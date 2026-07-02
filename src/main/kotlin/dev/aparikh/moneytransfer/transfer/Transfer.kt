package dev.aparikh.moneytransfer.transfer

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant

/** Lifecycle of a ledger row. [REVERSED]/[REVERSAL] are introduced at step 7 (undo). */
enum class TransferStatus { COMPLETED, REVERSED, REVERSAL }

/** An immutable ledger row recording money moved from [senderAccountId] to [recipientAccountId]. */
@Table("transfer")
data class Transfer(
    @Id
    val id: Long? = null,
    val senderAccountId: Long,
    val recipientAccountId: Long,
    val amount: BigDecimal,
    val currency: String = "USD",
    val purpose: String? = null,
    val status: TransferStatus = TransferStatus.COMPLETED,
    val createdAt: Instant = Instant.now(),
)
