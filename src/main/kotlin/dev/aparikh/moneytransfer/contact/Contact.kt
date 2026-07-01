package dev.aparikh.moneytransfer.contact

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * An address-book entry owned by [ownerAccountId] that points at the recipient's account via
 * [linkedAccountId]. The agent tools resolve a contact to [linkedAccountId] before any ledger
 * write, so a "contact" (who you know) and an "account" (where money lands) stay distinct.
 */
@Table("contact")
data class Contact(
    @Id
    val id: Long? = null,
    val ownerAccountId: Long,
    val name: String,
    val lastName: String? = null,
    val phoneNumber: String? = null,
    val linkedAccountId: Long,
)
