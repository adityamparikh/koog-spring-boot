package dev.aparikh.moneytransfer.contact

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

/** Spring Data JDBC repository for [Contact]. */
interface ContactRepository : CrudRepository<Contact, Long> {

    /** All contacts in the given owner's address book. */
    fun findByOwnerAccountId(ownerAccountId: Long): List<Contact>

    /**
     * Case-insensitive substring search over first name within one owner's address book.
     * Returns zero, one, or many matches — the "ambiguous recipient" case (e.g. two "Daniel"s).
     */
    @Query(
        "SELECT * FROM contact WHERE owner_account_id = :ownerAccountId " +
            "AND lower(name) LIKE lower(concat('%', :name, '%'))",
    )
    fun searchByName(
        @Param("ownerAccountId") ownerAccountId: Long,
        @Param("name") name: String,
    ): List<Contact>
}
