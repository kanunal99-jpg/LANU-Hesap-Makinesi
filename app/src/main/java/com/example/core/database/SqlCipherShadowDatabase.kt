package com.example.core.database

import android.content.Context
import android.util.Base64
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Real SQLCipher Room v4, but ONLY for isolated names used by the
 * explicit migration-acceptance test. It never touches
 * "lanu_calculator_db", its WAL/SHM, or any live app database.
 *
 * Encrypted fresh shadows are not user data cutovers. Passing this
 * experiment cannot switch AppDatabase.getDatabase() to SQLCipher.
 */
internal object SqlCipherShadowDatabase {
    private val shadowName = Regex("""lanu_ci_cipher_shadow_[a-z0-9_]{1,48}\.db""")

    fun isIsolatedName(name: String): Boolean = shadowName.matches(name)

    fun open(context: Context, name: String, randomKey: ByteArray): AppDatabase {
        require(isIsolatedName(name)) { "Canlı veritabanı yolu reddedildi." }
        require(randomKey.size == 32) { "256-bit veritabanı anahtarı gerekli." }
        // Load only for an explicit isolated acceptance probe. The same
        // library supports Room 2's SupportOpenHelperFactory at API 23+.
        System.loadLibrary("sqlcipher")
        val passphrase = Base64.encode(randomKey, Base64.NO_WRAP or
            Base64.NO_PADDING or Base64.URL_SAFE)
        try {
            return Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, name
            ).openHelperFactory(
                PreserveCorruptRoomFactory(SupportOpenHelperFactory(passphrase, null, true))
            ).build()
        } finally {
            passphrase.fill(0)
        }
    }
}
