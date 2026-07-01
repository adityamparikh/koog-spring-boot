package dev.aparikh.moneytransfer.contact

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** A contact as returned by the API. */
data class ContactResponse(
    val id: Long,
    val name: String,
    val lastName: String?,
    val phoneNumber: String?,
    val linkedAccountId: Long,
)

private fun Contact.toResponse() = ContactResponse(
    id = requireNotNull(id),
    name = name,
    lastName = lastName,
    phoneNumber = phoneNumber,
    linkedAccountId = linkedAccountId,
)

/** REST endpoints for listing and searching the acting user's contacts. */
@RestController
@RequestMapping("/api/v1/contacts")
class ContactController(private val contactService: ContactService) {

    /**
     * Lists the acting user's (`X-User-Id`) contacts, or — when [name] is provided — the
     * contacts whose first name matches (which may be empty or ambiguous).
     */
    @GetMapping
    fun list(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestParam(required = false) name: String?,
    ): List<ContactResponse> =
        (if (name.isNullOrBlank()) contactService.getContacts(userId) else contactService.findByName(userId, name))
            .map { it.toResponse() }
}
