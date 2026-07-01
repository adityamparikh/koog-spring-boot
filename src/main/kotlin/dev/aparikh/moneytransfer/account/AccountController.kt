package dev.aparikh.moneytransfer.account

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/** An account as returned by the API. */
data class AccountResponse(
    val id: Long,
    val ownerName: String,
    val currency: String,
    val balance: BigDecimal,
)

/** An account's current available balance. */
data class BalanceResponse(
    val accountId: Long,
    val balance: BigDecimal,
    val currency: String,
)

private fun Account.toResponse() = AccountResponse(
    id = requireNotNull(id),
    ownerName = ownerName,
    currency = currency,
    balance = balance,
)

/** REST endpoints for reading accounts and balances. */
@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(private val accountService: AccountService) {

    /** Returns the account with the given id. */
    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): AccountResponse = accountService.getAccount(id).toResponse()

    /** Returns the account's current balance. */
    @GetMapping("/{id}/balance")
    fun balance(@PathVariable id: Long): BalanceResponse =
        BalanceResponse(id, accountService.getBalance(id), "EUR")
}
