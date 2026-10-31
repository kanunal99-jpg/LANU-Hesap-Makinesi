package com.example

import com.example.features.communication.AccountDeletionConsentPolicy
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionConsentPolicyTest {
    private val code = "LANU-2345-6789-ABCD-EFGH-JKLM"

    @Test fun allConsentGatesAreRequiredBeforeAnyDestructiveRequest() {
        val accepted = AccountDeletionConsentPolicy::accepted
        assertTrue(accepted(code, code, "DELETE_ACCOUNT", true, true))
        assertFalse(accepted(code, code, "DELETE_ACCOUNT", false, true))
        assertFalse(accepted(code, code, "DELETE_ACCOUNT", true, false))
        assertFalse(accepted(code, "LANU-2345-6789-ABCD-EFGH-JKLN", "DELETE_ACCOUNT", true, true))
        assertFalse(accepted(code, code, "delete_account", true, true))
        assertFalse(accepted(code, code, " DELETE_ACCOUNT", true, true))
        assertFalse(accepted(code, code, "DELETE_ACCOUNT ", true, true))
        assertFalse(accepted("", "", "DELETE_ACCOUNT", true, true))
    }
}
