package com.example

import com.example.core.delivery.DeliveryImmediatePolicy
import com.example.core.delivery.DeliveryOptions
import com.example.core.delivery.DeliverySnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scheduling only; ciphertext/UUID stays in Room and is never work input. */
class DeliveryImmediatePolicyTest {
    @Test fun disabledAndUnreadablePreferencesCannotStartBackgroundDelivery() {
        assertFalse(DeliveryImmediatePolicy.allowed(DeliverySnapshot()))
        assertFalse(DeliveryImmediatePolicy.allowed(
            DeliverySnapshot(available = false, options = DeliveryOptions(enabled = true))))
    }

    @Test fun explicitConsentEnablesImmediateOutboxDrainForEitherNetworkPreference() {
        assertTrue(DeliveryImmediatePolicy.allowed(
            DeliverySnapshot(options = DeliveryOptions(enabled = true, unmeteredOnly = true))))
        assertTrue(DeliveryImmediatePolicy.allowed(
            DeliverySnapshot(options = DeliveryOptions(enabled = true, unmeteredOnly = false))))
    }

    @Test fun revokedConsentBlocksOldQueuedWorkOnItsNextExecution() {
        val enabled = DeliverySnapshot(options = DeliveryOptions(enabled = true))
        assertTrue(DeliveryImmediatePolicy.allowed(enabled))
        assertFalse(DeliveryImmediatePolicy.allowed(enabled.copy(
            options = enabled.options.copy(enabled = false))))
    }
}
