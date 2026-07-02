package dev.aparikh.moneytransfer.account

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import java.math.BigDecimal

/**
 * Spring Data JDBC repository for [Account].
 *
 * [debit] and [credit] are atomic conditional UPDATEs rather than read-modify-write: the
 * database computes the new balance and enforces the overdraft guard in a single statement,
 * so concurrent transfers on the same account cannot lose updates or overdraw.
 */
interface AccountRepository : CrudRepository<Account, Long> {

    /**
     * Subtracts [amount] from the account's balance only if it stays non-negative.
     *
     * @return rows affected — `1` on success, `0` when funds are insufficient
     *         (given the caller has already confirmed the account exists).
     */
    @Modifying
    @Query(
        "UPDATE account SET balance = balance - :amount " +
            "WHERE id = :id AND balance >= :amount",
    )
    fun debit(@Param("id") id: Long, @Param("amount") amount: BigDecimal): Int

    /** Adds [amount] to the account's balance. */
    @Modifying
    @Query("UPDATE account SET balance = balance + :amount WHERE id = :id")
    fun credit(@Param("id") id: Long, @Param("amount") amount: BigDecimal): Int
}
