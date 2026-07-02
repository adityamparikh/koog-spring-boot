package dev.aparikh.moneytransfer.contact

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * A directed edge in an owner's address book: [ownerAccountId] knows [contactAccountId].
 *
 * Like a Venmo "friend", a contact is a thin reference into the shared account graph — it does
 * NOT copy the friend's name or phone (those live on the linked [Account], the single source of
 * truth). An optional [nickname] lets the owner label the contact; when absent, display falls
 * back to the linked account's name.
 */
@Table("contact")
data class Contact(
    @Id
    val id: Long? = null,
    val ownerAccountId: Long,
    val contactAccountId: Long,
    val nickname: String? = null,
)
