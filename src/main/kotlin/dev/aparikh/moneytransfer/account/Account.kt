package dev.aparikh.moneytransfer.account

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal

/**
 * A person's profile and wallet. The [id] is the person's identity in this application
 * (there is no separate user table). This account is the single source of truth for display
 * information ([firstName], [lastName], [phoneNumber]) — contacts reference an account rather
 * than duplicating it. Currency is USD; [balance] is never negative.
 */
@Table("account")
data class Account(
    @Id
    val id: Long? = null,
    val firstName: String,
    val balance: BigDecimal,
    val lastName: String? = null,
    val phoneNumber: String? = null,
    val currency: String = "USD",
)
