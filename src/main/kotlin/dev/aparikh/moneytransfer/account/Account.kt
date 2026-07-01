package dev.aparikh.moneytransfer.account

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal

/**
 * An account holding a person's money. The [id] doubles as the person's identity in this
 * application (there is no separate user table). Currency is EUR; [balance] is never negative.
 */
@Table("account")
data class Account(
    @Id
    val id: Long? = null,
    val ownerName: String,
    val currency: String = "EUR",
    val balance: BigDecimal,
)
