package dev.aparikh.moneytransfer.contact

import dev.aparikh.moneytransfer.account.AccountRepository
import dev.aparikh.moneytransfer.common.UnknownAccountException
import org.springframework.stereotype.Service

/**
 * A contact resolved for display: identity comes from the [Contact] edge, while the name and
 * phone are sourced live from the linked [dev.aparikh.moneytransfer.account.Account].
 */
data class ResolvedContact(
    val contactId: Long,
    val contactAccountId: Long,
    val displayName: String,
    val nickname: String?,
    val phoneNumber: String?,
)

/** Contact lookup for an owner account; resolves display info from the linked account. */
@Service
class ContactService(
    private val contacts: ContactRepository,
    private val accounts: AccountRepository,
) {

    /** All contacts owned by [accountId], resolved for display. */
    fun getContacts(accountId: Long): List<ResolvedContact> =
        contacts.findByOwnerAccountId(accountId).map { it.resolve() }

    /** Contacts of [accountId] matching [name] by linked-account name or nickname (may be ambiguous). */
    fun findByName(accountId: Long, name: String): List<ResolvedContact> =
        contacts.searchByName(accountId, name).map { it.resolve() }

    private fun Contact.resolve(): ResolvedContact {
        val account = accounts.findById(contactAccountId)
            .orElseThrow { UnknownAccountException(contactAccountId) }
        val fullName = listOfNotNull(account.firstName, account.lastName).joinToString(" ")
        return ResolvedContact(
            contactId = requireNotNull(id),
            contactAccountId = contactAccountId,
            displayName = nickname ?: fullName,
            nickname = nickname,
            phoneNumber = account.phoneNumber,
        )
    }
}
