package dev.aparikh.moneytransfer.contact

import org.springframework.stereotype.Service

/** Contact lookup for an owner account. */
@Service
class ContactService(private val contacts: ContactRepository) {

    /** All contacts owned by [accountId]. */
    fun getContacts(accountId: Long): List<Contact> = contacts.findByOwnerAccountId(accountId)

    /** Contacts of [accountId] whose first name matches [name] (may be empty or ambiguous). */
    fun findByName(accountId: Long, name: String): List<Contact> =
        contacts.searchByName(accountId, name)
}
