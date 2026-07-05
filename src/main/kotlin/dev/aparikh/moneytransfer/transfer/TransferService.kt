package dev.aparikh.moneytransfer.transfer

import dev.aparikh.moneytransfer.account.AccountRepository
import dev.aparikh.moneytransfer.common.InsufficientFundsException
import dev.aparikh.moneytransfer.common.InvalidAmountException
import dev.aparikh.moneytransfer.common.TransferNotCancellableException
import dev.aparikh.moneytransfer.common.UnknownAccountException
import dev.aparikh.moneytransfer.common.UnknownTransferException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * The transfer state machine (step 7 — async settlement, lifecycle L5/L6).
 *
 * Money moves in two separate steps instead of one transaction:
 *  - [initiate] (L5): atomically debit the sender and record a `PENDING` ledger row — funds are
 *    *reserved*, so available balance is honest while settlement is in flight.
 *  - settlement (L6): the [TransferSettler] credits the recipient once `settle_at` passes.
 *
 * [cancel] undoes a transfer **only while `PENDING`** — a settled transfer is irreversible by
 * design. Every transition out of `PENDING` is an atomic conditional UPDATE, so a racing cancel
 * and settle resolve to exactly one winner; only the winner touches balances.
 */
@Service
class TransferService(
    private val accounts: AccountRepository,
    private val transfers: TransferRepository,
    private val properties: TransferProperties,
) {

    /**
     * Debits [senderAccountId] and records the transfer as `PENDING`, due for settlement after
     * [TransferProperties.settlementDelay]. The recipient is **not** credited here.
     *
     * @throws InvalidAmountException if [amount] is not strictly positive
     * @throws UnknownAccountException if either account does not exist
     * @throws InsufficientFundsException if the sender cannot cover [amount]
     */
    @Transactional
    fun initiate(
        senderAccountId: Long,
        recipientAccountId: Long,
        amount: BigDecimal,
        purpose: String?,
    ): Transfer {
        if (amount.signum() <= 0) throw InvalidAmountException(amount)
        // Existence is checked first so that a subsequent debit of 0 rows unambiguously means
        // "insufficient funds" rather than "no such account".
        if (!accounts.existsById(senderAccountId)) throw UnknownAccountException(senderAccountId)
        if (!accounts.existsById(recipientAccountId)) throw UnknownAccountException(recipientAccountId)

        if (accounts.debit(senderAccountId, amount) == 0) {
            throw InsufficientFundsException(senderAccountId, amount)
        }

        return transfers.save(
            Transfer(
                senderAccountId = senderAccountId,
                recipientAccountId = recipientAccountId,
                amount = amount,
                purpose = purpose,
                status = TransferStatus.PENDING,
                settleAt = Instant.now() + properties.settlementDelay,
            ),
        )
    }

    /**
     * Cancels a `PENDING` transfer and re-credits the sender. Only the sender may cancel, and
     * only during the settlement window.
     *
     * @throws UnknownTransferException if the transfer does not exist or [actingAccountId] is not its sender
     * @throws TransferNotCancellableException if the transfer has already left `PENDING`
     */
    @Transactional
    fun cancel(transferId: Long, actingAccountId: Long): Transfer {
        val transfer = transfers.findByIdOrNull(transferId) ?: throw UnknownTransferException(transferId)
        // Sender-only, reported as "not found" rather than 403: don't leak other users' transfers.
        if (transfer.senderAccountId != actingAccountId) throw UnknownTransferException(transferId)

        // The conditional flip is the race arbiter: if the settler got there first, this update
        // count is 0 and no money moves here.
        if (transfers.markCancelled(transferId) == 0) {
            val current = transfers.findByIdOrNull(transferId)?.status ?: TransferStatus.SETTLED
            throw TransferNotCancellableException(transferId, current.name)
        }
        // Defense in depth: a 0-row credit would silently destroy money, so fail the transaction
        // instead (unreachable today — the ledger FK prevents deleting an account with transfers).
        check(accounts.credit(transfer.senderAccountId, transfer.amount) == 1) {
            "refund for cancelled transfer $transferId matched no sender account — rolling back"
        }
        return transfer.copy(status = TransferStatus.CANCELLED)
    }

    /**
     * Settles one due transfer (called per row by [TransferSettler], each in its own transaction):
     * credit the recipient and flip `PENDING → SETTLED` — or, if the recipient no longer exists,
     * flip `PENDING → FAILED` and re-credit the sender. Losing the race to a concurrent cancel
     * must move no money. Safe to call with an id that is missing or already terminal (no-op).
     */
    @Transactional
    fun settle(transferId: Long) {
        val transfer = transfers.findByIdOrNull(transferId) ?: return
        if (transfer.status != TransferStatus.PENDING) return

        // Recipient vanished since initiation: the money must go back to the sender — but only
        // behind a winning flip, like every other credit here. (Defensive: the ledger FK means an
        // account with transfers can't actually be deleted today; FR-20 mandates the path anyway.)
        if (!accounts.existsById(transfer.recipientAccountId)) {
            if (transfers.markFailed(transferId) == 1) {
                check(accounts.credit(transfer.senderAccountId, transfer.amount) == 1) {
                    "refund for FAILED transfer $transferId matched no sender account — rolling back"
                }
            }
            return
        }

        // The flip is the race arbiter: 0 rows means a concurrent cancel won — move no money.
        // The credit check is defense in depth: a 0-row credit must roll the flip back too.
        if (transfers.markSettled(transferId) == 1) {
            check(accounts.credit(transfer.recipientAccountId, transfer.amount) == 1) {
                "credit for settled transfer $transferId matched no recipient account — rolling back"
            }
        }
    }

    /** Transfers visible to [accountId] (sent or received), most recent first. */
    fun transfersFor(accountId: Long): List<Transfer> = transfers.findAllForAccount(accountId)

    /** The most recent transfers visible to [accountId], bounded — the agent tool's view. */
    fun recentTransfersFor(accountId: Long, limit: Int): List<Transfer> =
        transfers.findRecentForAccount(accountId, limit)

    /** The acting sender's most recent `PENDING` transfer, if any — the target of "undo". */
    fun latestPendingFor(senderAccountId: Long): Transfer? =
        transfers.findLatestPendingFor(senderAccountId)
}
