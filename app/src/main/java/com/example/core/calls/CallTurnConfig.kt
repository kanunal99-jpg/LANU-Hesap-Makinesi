package com.example.core.calls

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Optional user-operated relay. No shared/public credentials, account creation or billing. */
class CallTurnConfig(context: Context) {
    class Value(val url: String, val username: String, val credential: String) {
        override fun toString() = "TurnConfig(redacted)"
    }
    private val prefs by lazy {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "lanu_turn", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    fun read(): Value? {
        val url = prefs.getString("url", null) ?: return null
        return Value(url, prefs.getString("user", "").orEmpty(), prefs.getString("password", "").orEmpty()).also(::validate)
    }
    fun save(value: Value) {
        validate(value)
        check(prefs.edit().putString("url", value.url).putString("user", value.username)
            .putString("password", value.credential).commit())
        val readBack = read()
        check(readBack?.url == value.url && readBack.username == value.username && readBack.credential == value.credential)
    }
    fun clear() { check(prefs.edit().clear().commit()) }
    companion object {
        fun validate(value: Value) {
            val match = Regex("^(turns?):([A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?):([0-9]{1,5})\\?transport=(udp|tcp)$").matchEntire(value.url)
            require(match != null && match.groupValues[3].toInt() in 1..65535)
            require(match.groupValues[1] != "turns" || match.groupValues[4] == "tcp")
            require(value.username.length in 1..256 && value.credential.length in 1..512)
            require(value.username.none { it.isISOControl() } && value.credential.none { it.isISOControl() })
        }
    }
}
