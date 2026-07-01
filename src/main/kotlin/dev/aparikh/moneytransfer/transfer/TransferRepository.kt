package dev.aparikh.moneytransfer.transfer

import org.springframework.data.repository.CrudRepository

/** Spring Data JDBC repository for the [Transfer] ledger. */
interface TransferRepository : CrudRepository<Transfer, Long> {

    /** A sender's transfers, most recent first. */
    fun findBySenderAccountIdOrderByCreatedAtDesc(senderAccountId: Long): List<Transfer>
}
