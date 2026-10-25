package com.example.core.delivery

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** Not a credential store. No message, peer code, PIN, token or key belongs here. */
class AndroidDeliveryStore(context: Context) : DeliveryStore {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun read(): DeliverySnapshot = try {
        val all = preferences.all
        val enabled = all[ENABLED]
        val unmetered = all[UNMETERED]
        check(enabled == null || enabled is Boolean)
        check(unmetered == null || unmetered is Boolean)
        DeliverySnapshot(
            options = DeliveryOptions(enabled as? Boolean ?: false, unmetered as? Boolean ?: true),
            lastAttemptAt = (all[ATTEMPT] as? Long)?.coerceAtLeast(0L) ?: 0L,
            lastCheckedAt = (all[CHECKED] as? Long)?.coerceAtLeast(0L) ?: 0L,
            outcome = DeliveryOutcome.entries.firstOrNull { it.name == all[OUTCOME] } ?: DeliveryOutcome.NEVER
        )
    } catch (_: Exception) { DeliverySnapshot(available = false) }

    override fun saveOptions(options: DeliveryOptions) {
        check(preferences.edit().putBoolean(ENABLED, options.enabled)
            .putBoolean(UNMETERED, options.unmeteredOnly).commit()) { "Teslimat tercihi kaydedilemedi." }
    }

    override fun attempt(at: Long) {
        check(preferences.edit().putLong(ATTEMPT, at.coerceAtLeast(0L)).commit())
    }

    override fun finish(outcome: DeliveryOutcome, at: Long) {
        val edit = preferences.edit().putString(OUTCOME, outcome.name)
        if (outcome == DeliveryOutcome.CHECKED) edit.putLong(CHECKED, at.coerceAtLeast(0L))
        check(edit.commit())
    }

    fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val NAME = "lanu_delivery"
        private const val ENABLED = "background_enabled"
        private const val UNMETERED = "unmetered_only"
        private const val ATTEMPT = "last_attempt_at"
        private const val CHECKED = "last_checked_at"
        private const val OUTCOME = "last_outcome"
    }
}
