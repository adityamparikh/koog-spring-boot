package dev.aparikh.moneytransfer.transfer

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Settles due transfers (step 7, lifecycle L6) — the async half of the debit/credit split.
 *
 * Outbox-style by design: the `transfer` table **is** the work queue (`status = 'PENDING' AND
 * settle_at <= now`), so settlement survives restarts for free — there is no in-memory event to
 * lose. Each row settles in its own transaction ([TransferService.settle]), so one poisoned row
 * cannot roll back the batch, and re-delivery after a crash is harmless: the conditional
 * `PENDING →` flips no-op on rows that already settled.
 */
@Component
class TransferSettler(
    private val transfers: TransferRepository,
    private val transferService: TransferService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Polls for due `PENDING` transfers; interval from `app.transfer.settlement-poll-ms`. */
    @Scheduled(fixedDelayString = "\${app.transfer.settlement-poll-ms:5000}")
    fun settleDueTransfers() {
        transfers.findDueForSettlement(Instant.now()).forEach { transfer ->
            runCatching { transferService.settle(requireNotNull(transfer.id)) }
                .onFailure { logger.error("Settlement of transfer {} failed; will retry next poll", transfer.id, it) }
        }
    }
}
