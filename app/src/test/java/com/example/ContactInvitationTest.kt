package com.example

import com.example.features.communication.ContactInvitation
import org.junit.Assert.*
import org.junit.Test

class ContactInvitationTest {
    private val code = "LANU-2345-6789-ABCD-EFGH-JKLM"
    @Test fun invitationAndManualCodeImportMatchWithoutOpeningAnyActivity() {
        assertEquals(code, ContactInvitation.decode(ContactInvitation.encode(code)))
        assertEquals(code, ContactInvitation.decode(code))
    }
    @Test fun urlAndOversizeAndWrongAlphabetAreRejected() {
        for (value in listOf("https://example.com/$code", "x".repeat(257), "LANU-0000-0000-0000-0000-0000")) {
            assertThrows(IllegalArgumentException::class.java) { ContactInvitation.decode(value) }
        }
    }
}
