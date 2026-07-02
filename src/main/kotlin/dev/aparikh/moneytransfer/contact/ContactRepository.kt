package dev.aparikh.moneytransfer.contact

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

/** Spring Data JDBC repository for [Contact]. */
interface ContactRepository : CrudRepository<Contact, Long> {

    /** All contacts in the given owner's address book. */
    fun findByOwnerAccountId(ownerAccountId: Long): List<Contact>

    /**
     * Case-insensitive search within one owner's address book, matching either the linked
     * account's first name or the owner-assigned nickname. Returns zero, one, or many matches —
     * the "ambiguous recipient" case (e.g. two "Daniel"s). Joins to `account` because the name
     * lives there, not on the contact.
     */
    @Query(
        """
        SELECT c.* FROM contact c
        JOIN account a ON a.id = c.contact_account_id
        WHERE c.owner_account_id = :ownerAccountId
          AND (lower(a.first_name) LIKE lower(concat('%', :name, '%'))
               OR lower(coalesce(c.nickname, '')) LIKE lower(concat('%', :name, '%')))
        """,
    )
    fun searchByName(
        @Param("ownerAccountId") ownerAccountId: Long,
        @Param("name") name: String,
    ): List<Contact>
}
