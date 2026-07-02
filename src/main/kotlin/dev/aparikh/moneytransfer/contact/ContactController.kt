package dev.aparikh.moneytransfer.contact

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** A contact as returned by the API; display name/phone come from the linked account. */
data class ContactResponse(
    val contactId: Long,
    val contactAccountId: Long,
    val displayName: String,
    val nickname: String?,
    val phoneNumber: String?,
)

private fun ResolvedContact.toResponse() = ContactResponse(
    contactId = contactId,
    contactAccountId = contactAccountId,
    displayName = displayName,
    nickname = nickname,
    phoneNumber = phoneNumber,
)

/** REST endpoints for listing and searching the acting user's contacts. */
@RestController
@RequestMapping("/api/v1/contacts")
class ContactController(private val contactService: ContactService) {

    /**
     * Lists the acting user's (`X-User-Id`) contacts, or — when [name] is provided — the
     * contacts whose linked-account name or nickname matches (which may be empty or ambiguous).
     */
    @GetMapping
    fun list(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestParam(required = false) name: String?,
    ): List<ContactResponse> =
        (if (name.isNullOrBlank()) contactService.getContacts(userId) else contactService.findByName(userId, name))
            .map { it.toResponse() }
}
