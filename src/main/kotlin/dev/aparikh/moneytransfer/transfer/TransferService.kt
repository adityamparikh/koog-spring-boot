package dev.aparikh.moneytransfer.transfer

import dev.aparikh.moneytransfer.account.AccountRepository
import dev.aparikh.moneytransfer.common.InsufficientFundsException
import dev.aparikh.moneytransfer.common.InvalidAmountException
import dev.aparikh.moneytransfer.common.UnknownAccountException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** Moves money between accounts atomically and records the ledger row. */
@Service
class TransferService(
    private val accounts: AccountRepository,
    private val transfers: TransferRepository,
) {

    /**
     * Debits [senderAccountId], credits [recipientAccountId], and records a [Transfer], all in
     * one transaction.
     *
     * @throws InvalidAmountException if [amount] is not strictly positive
     * @throws UnknownAccountException if either account does not exist
     * @throws InsufficientFundsException if the sender cannot cover [amount]
     */
    @Transactional
    fun transfer(
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
        accounts.credit(recipientAccountId, amount)

        return transfers.save(
            Transfer(
                senderAccountId = senderAccountId,
                recipientAccountId = recipientAccountId,
                amount = amount,
                purpose = purpose,
                status = TransferStatus.COMPLETED,
            ),
        )
    }
}
