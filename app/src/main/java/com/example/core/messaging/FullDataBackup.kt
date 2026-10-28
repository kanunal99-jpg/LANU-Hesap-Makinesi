package com.example.core.messaging

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable, authenticated full archive. No raw SQLite/WAL or plaintext temporary file is exported. */
object FullDataBackup {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val ITERATIONS = 210000
    private const val MAX_ROWS = 100000
    private val magic = "LANUBK01".toByteArray(Charsets.US_ASCII)
    private val tables = linkedMapOf(
        "calculation_history" to listOf("id", "expression", "result", "timestamp"),
        "user_profiles" to listOf("phoneNumber", "displayName", "avatarUrl", "registeredAt"),
        "app_settings" to listOf("key", "value"),
        "contacts" to listOf("phoneNumber", "name", "isBlocked", "avatarUrl", "statusText"),
        "conversations" to listOf("id", "partnerPhone", "partnerName", "lastMessageText", "lastMessageTime", "isGroup", "unreadCount"),
        "messages" to listOf("id", "conversationId", "senderPhone", "senderName", "content", "mediaUrl", "timestamp", "status", "reaction"),
        "call_logs" to listOf("id", "partnerName", "partnerPhone", "isVideo", "durationSeconds", "isOutgoing", "timestamp")
    )
    data class Summary(val messages: Int, val contacts: Int, val calculations: Int) {
        fun description() = "$messages mesaj, $contacts kişi, $calculations hesap kaydı"
    }
    class Opened internal constructor(val identity: MessagingIdentity, internal val payload: JSONObject) {
        val summary: Summary get() = Summary(rows("messages").length(), rows("contacts").length(), rows("calculation_history").length())
        internal fun rows(table: String): JSONArray = payload.getJSONObject("tables").getJSONArray(table)
        fun displayName(): String = rows("user_profiles").optJSONArray(0)?.optString(1)?.take(60) ?: "LANU Kullanıcısı"
    }

    fun capture(db: SupportSQLiteDatabase, identity: MessagingIdentity): JSONObject {
        require(UserCodes.isConsistentIdentity(identity)) { "Kimlik doğrulanamadı; mevcut kayıtlar korunuyor." }
        val data = JSONObject()
        var count = 0
        var estimatedBytes = 0L
        for ((table, columns) in tables) {
            val rows = JSONArray()
            db.query("SELECT " + columns.joinToString(",") { "`$it`" } + " FROM `$table` ORDER BY `${columns.first()}`").use { cursor ->
                while (cursor.moveToNext()) {
                    require(++count <= MAX_ROWS) { "Yedekteki kayıt sayısı sınırı aşıldı." }
                    val row = row(cursor)
                    estimatedBytes += row.toString().toByteArray(Charsets.UTF_8).size
                    require(estimatedBytes <= MAX_BYTES - 16384L) { "Tam yedek 32 MiB sınırını aşıyor. Mevcut veriler değiştirilmedi." }
                    rows.put(row)
                }
            }
            data.put(table, rows)
        }
        return JSONObject().put("format", "LANU-full-v1").put("schema", 4)
            .put("createdAt", System.currentTimeMillis()).put("tables", data)
            .put("identity", JSONObject().put("id", identity.id).put("code", identity.code).put("nonce", identity.nonce)
                .put("publicKey", identity.publicKey).put("privateKey", identity.privateKey))
    }

    fun seal(payload: JSONObject, password: String): ByteArray {
        require(password.length in 12..256) { "Yedek parolası 12–256 karakter olmalıdır." }
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_BYTES) { "Tam yedek 32 MiB sınırını aşıyor." }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val header = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { it.write(magic); it.writeInt(ITERATIONS); it.write(salt); it.write(iv) } }.toByteArray()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            header + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }

    fun open(bytes: ByteArray, password: String): Opened {
        require(password.length in 12..256 && bytes.size in 58..(MAX_BYTES + 56)) { "Yedek dosyası veya parola geçersiz." }
        return try {
            val input = DataInputStream(bytes.inputStream())
            val fileMagic = ByteArray(8).also(input::readFully)
            require(fileMagic.contentEquals(magic) && input.readInt() == ITERATIONS)
            val salt = ByteArray(16).also(input::readFully)
            val iv = ByteArray(12).also(input::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(bytes.copyOfRange(0, 40))
            val plain = cipher.doFinal(bytes, 40, bytes.size - 40)
            val payload = try {
                validateJsonDepth(plain)
                JSONObject(String(plain, Charsets.UTF_8))
            } finally { plain.fill(0) }
            validate(payload)
        } catch (_: Exception) { throw IllegalArgumentException("Tam yedek açılamadı. Dosyayı ve parolanızı kontrol edin; mevcut kayıtlar değiştirilmedi.") }
    }

    fun readFile(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val block = ByteArray(8192)
        var stalled = 0
        while (true) {
            val n = input.read(block)
            if (n < 0) break
            if (n == 0) { require(++stalled < 3) { "Yedek sağlayıcısı veri göndermedi." }; continue }
            stalled = 0
            require(out.size().toLong() + n <= MAX_BYTES + 56L) { "Yedek dosyası güvenli boyut sınırını aşıyor." }
            out.write(block, 0, n)
        }
        return out.toByteArray()
    }

    /** Must run inside the same Room transaction as import. Existing rows are never overwritten. */
    fun checkConflicts(db: SupportSQLiteDatabase, opened: Opened) {
        for ((table, columns) in tables) {
            if (table in setOf("app_settings", "user_profiles", "calculation_history", "call_logs")) continue
            val rows = opened.rows(table)
            for (i in 0 until rows.length()) {
                val values = rows.getJSONArray(i)
                db.query("SELECT " + columns.joinToString(",") { "`$it`" } + " FROM `$table` WHERE `${columns.first()}` = ?", arrayOf(values.get(0))).use { existing ->
                    if (existing.moveToFirst()) require(row(existing).toString() == values.toString()) {
                        "Bu cihazda farklı kayıtlar var. Üzerine yazılmadı; yedeği boş bir kurulumda geri yükleyin."
                    }
                }
            }
        }
    }

    fun importMissing(db: SupportSQLiteDatabase, opened: Opened) {
        for ((table, columns) in tables) {
            if (table == "user_profiles") continue // Repository coordinates the identity/profile pair.
            val sql = "INSERT OR IGNORE INTO `$table` (" + columns.joinToString(",") { "`$it`" } + ") VALUES (" + columns.joinToString(",") { "?" } + ")"
            val rows = opened.rows(table)
            for (i in 0 until rows.length()) {
                val values = rows.getJSONArray(i)
                val args = Array<Any?>(values.length()) { n -> values.get(n).takeUnless { it === JSONObject.NULL } }
                if (table == "calculation_history" || table == "call_logs") {
                    // Device-local integer IDs are not portable identities. Merge by
                    // complete record content so retries don't duplicate history.
                    val match = columns.drop(1).joinToString(" AND ") { "`$it` IS ?" }
                    val exists = db.query("SELECT 1 FROM `$table` WHERE $match LIMIT 1", args.drop(1).toTypedArray()).use { it.moveToFirst() }
                    if (!exists) {
                        args[0] = null // Allocate a new local INTEGER PRIMARY KEY.
                        db.execSQL(sql, args)
                    }
                } else db.execSQL(sql, args)
            }
        }
    }

    /**
     * Read back EVERY archived record in the same Room transaction, before COMMIT.
     * INSERT OR IGNORE / RAISE(IGNORE) can silently skip a record; pre-import
     * conflict checks cannot detect this. Never print archived values or paths.
     */
    fun verifyImported(db: SupportSQLiteDatabase, opened: Opened) {
        for ((table, columns) in tables) {
            if (table == "user_profiles") continue // Restored by the repository.
            val rows = opened.rows(table)
            for (i in 0 until rows.length()) {
                val expected = rows.getJSONArray(i)
                val verified = when (table) {
                    "calculation_history", "call_logs" -> {
                        // Numeric row IDs are local to each device. All other
                        // fields must exist in one real row, without alteration.
                        val conditions = columns.drop(1).joinToString(" AND ") { "`$it` IS ?" }
                        val args = Array<Any?>(expected.length() - 1) { n ->
                            expected.get(n + 1).takeUnless { it === JSONObject.NULL }
                        }
                        db.query("SELECT 1 FROM `$table` WHERE $conditions LIMIT 1", args)
                            .use { it.moveToFirst() }
                    }
                    "app_settings" -> {
                        // Existing PIN and lockout settings intentionally win
                        // over imported settings; require key existence only.
                        db.query("SELECT 1 FROM `app_settings` WHERE `key` = ? LIMIT 1",
                            arrayOf(expected.getString(0))).use { it.moveToFirst() }
                    }
                    else -> {
                        val query = "SELECT " + columns.joinToString(",") { "`$it`" } +
                            " FROM `$table` WHERE `${columns.first()}` = ? LIMIT 1"
                        db.query(query, arrayOf(expected.get(0))).use { actual ->
                            actual.moveToFirst() && row(actual).toString() == expected.toString()
                        }
                    }
                }
                require(verified) {
                    "Şifreli yedek kaydı doğrulanamadı. Mevcut kayıtlar korunuyor."
                }
            }
        }
    }

    private fun validate(payload: JSONObject): Opened {
        require(payload.getString("format") == "LANU-full-v1" && payload.getInt("schema") == 4)
        require(payload.keys().asSequence().toSet() == setOf("format", "schema", "createdAt", "tables", "identity"))
        val own = payload.getJSONObject("identity")
        require(own.keys().asSequence().toSet() == setOf("id", "code", "nonce", "publicKey", "privateKey"))
        require(own.getString("id").length == 64 && own.getString("code").length == 29 && own.getString("nonce").length == 22)
        require(own.getString("publicKey").length in 64..2048 && own.getString("privateKey").length in 64..2048)
        val identity = MessagingIdentity(own.getString("id"), own.getString("code"), own.getString("nonce"), own.getString("publicKey"), own.getString("privateKey"))
        require(UserCodes.isConsistentIdentity(identity))
        val data = payload.getJSONObject("tables")
        require(data.keys().asSequence().toSet() == tables.keys)
        var count = 0
        for ((table, columns) in tables) {
            val rows = data.getJSONArray(table)
            val ids = hashSetOf<String>()
            for (i in 0 until rows.length()) {
                require(++count <= MAX_ROWS)
                val row = rows.getJSONArray(i)
                require(row.length() == columns.size && !row.isNull(0) && ids.add(row.get(0).toString()))
                for (n in 0 until row.length()) {
                    val value = row.get(n)
                    require(value === JSONObject.NULL || value is String || value is Int || value is Long)
                    if (value is String) require(value.length <= 100000)
                }
            }
        }
        require(data.getJSONArray("user_profiles").length() <= 1)
        data.getJSONArray("user_profiles").optJSONArray(0)?.let { require(it.getString(0) == identity.code) }
        return Opened(identity, payload)
    }

    private fun row(cursor: Cursor): JSONArray = JSONArray().also { row ->
        for (n in 0 until cursor.columnCount) row.put(when (cursor.getType(n)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(n)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(n)
            else -> throw IllegalArgumentException("Desteklenmeyen yedek veri türü.")
        })
    }

    private fun validateJsonDepth(bytes: ByteArray) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (byte in bytes) {
            val c = byte.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 12) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        catch (_: java.security.NoSuchAlgorithmException) { IdentityBackup.softwarePbkdf2(password, salt, ITERATIONS) }
        finally { spec.clearPassword() }
        return try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
    }
}
