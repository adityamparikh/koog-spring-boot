package dev.aparikh.moneytransfer.account

import dev.aparikh.moneytransfer.common.UnknownAccountException
import org.springframework.stereotype.Service
import java.math.BigDecimal

/** Read access to accounts and balances. */
@Service
class AccountService(private val accounts: AccountRepository) {

    /** @throws UnknownAccountException if no account has the given [id]. */
    fun getAccount(id: Long): Account =
        accounts.findById(id).orElseThrow { UnknownAccountException(id) }

    /** @throws UnknownAccountException if no account has the given [id]. */
    fun getBalance(id: Long): BigDecimal = getAccount(id).balance
}
