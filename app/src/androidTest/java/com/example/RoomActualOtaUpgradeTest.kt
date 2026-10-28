package com.example

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.*
import com.example.core.messaging.EncryptedMessagingIdentityStore
import com.example.core.messaging.PeerMessageCrypto
import com.example.core.messaging.UserCodes
import com.example.core.security.PinCredentialCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * CI-only TWO-PHASE REAL APK OTA contract, not the user's phone.
 *
 * Phase 1 runs instrumentation compiled from the new tree against the previous
 * publicly signed, installed APK and seeds real Room v4 + Keystore-backed data.
 * The CI runner then performs adb install -r WITHOUT pm clear or uninstall.
 * Phase 2 runs after installing the new, same-certificate release APK.
 *
 * Never enable on a physical device or during the normal Android test suite.
 */
@RunWith(AndroidJUnit4::class)
class RoomActualOtaUpgradeTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun gate(phase: String) {
        val args = InstrumentationRegistry.getArguments()
        val emulator = Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)
        assumeTrue("Explicit isolated-emulator acceptance only",
            emulator && args.getString("lanu_ota_ci") == "isolated-emulator-v1" &&
                args.getString("lanu_ota_phase") == phase)
        val expected = args.getString("lanu_expected_version")?.toLongOrNull()
            ?: error("OTA expected version not provided")
        @Suppress("DEPRECATION")
        val actual = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertEquals("Wrong installed APK; upgrade experiment is invalid", expected, actual)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private val sentinels: android.content.SharedPreferences
        get() = context.getSharedPreferences("lanu_ci_ota_preservation_probe", Context.MODE_PRIVATE)

    private val messageId = "lanu-ci-ota-pending-1"
    private val conversationId = "lanu-ci-ota-conversation-1"

    @Test
    fun seedPreviouslyInstalledStableRelease() = runBlocking {
        gate("seed")
        assertNull("Never overwrite a previous probe", sentinels.getString("probe", null))
        val identityStore = EncryptedMessagingIdentityStore(context)
        assertNull("Seed only a fresh isolated emulator", identityStore.read())
        val identity = UserCodes.create()
        identityStore.save(identity)
        val pin = PinCredentialCodec.create("fixture-only-739188")
        val cipher = PeerMessageCrypto.encrypt(
            identity, identity.id, identity.publicKey, messageId,
            "OTA encrypted test message"
        )
        val database = AppDatabase.getDatabase(context)
        assertNull(database.settingsDao().getSettingValue("pin_hash"))
        database.run {
            settingsDao().insertSetting(SettingsEntity("pin_hash", pin))
            settingsDao().insertSetting(SettingsEntity("entry_pin_enabled", "true"))
            settingsDao().insertSetting(SettingsEntity("pin_attempt_state", "2:0"))
            settingsDao().insertSetting(SettingsEntity("lanu_ota_probe_private_marker", "KEEP_MARKER"))
            userProfileDao().insertProfile(UserProfileEntity(identity.code, "OTA Owner", registeredAt = 41L))
            contactDao().insertContact(ContactEntity("LANU-OTA-PEER", "OTA Contact", isBlocked = true))
            conversationDao().insertConversation(ConversationEntity(
                conversationId, "LANU-OTA-PEER", "OTA Contact", unreadCount = 4,
                lastMessageText = "Şifreli mesaj", lastMessageTime = 42L
            ))
            messageDao().insertMessage(MessageEntity(
                messageId, conversationId, identity.id, "OTA Owner",
                cipher, timestamp = 43L, status = "PENDING"
            ))
            calculationHistoryDao().insertHistory(CalculationHistoryEntity(
                id = 44001L, expression = "40+2", result = "42", timestamp = 44L
            ))
            callLogDao().insertCallLog(CallLogEntity(
                id = 55001L, partnerName = "OTA Contact", partnerPhone = "LANU-OTA-PEER",
                isVideo = false, durationSeconds = 5, isOutgoing = true, timestamp = 45L
            ))
        }
        val persisted = sentinels.edit()
            .putString("probe", "prepared")
            .putString("identity_digest", sha256(identity.privateKey + identity.publicKey + identity.id + identity.code))
            .putString("pin_digest", sha256(pin))
            .putString("cipher_digest", sha256(cipher))
            .commit()
        assertTrue("The pre-update sentinel must be durably saved", persisted)
        assertEquals(messageId, database.messageDao().getPendingMessages(
            now = System.currentTimeMillis(), limit = 200).single().id)
    }

    @Test
    fun verifySameAccountAndAllSevenTablesAfterRealApkReplacement() = runBlocking {
        gate("verify")
        assertEquals("The previous APK's private prefs must survive install -r",
            "prepared", sentinels.getString("probe", null))
        val identity = EncryptedMessagingIdentityStore(context).read()
        assertNotNull("Keystore-backed account lost after APK install -r", identity)
        assertTrue("Recovered private key must verify the stored public identity",
            UserCodes.isConsistentIdentity(identity!!))
        assertEquals("Keystore account changed or reset",
            sentinels.getString("identity_digest", null),
            sha256(identity.privateKey + identity.publicKey + identity.id + identity.code))
        val db = AppDatabase.getDatabase(context)
        val pin = db.settingsDao().getSettingValue("pin_hash")
        assertNotNull("PIN verifier was deleted", pin)
        assertEquals("PIN verifier changed across upgrade",
            sentinels.getString("pin_digest", null), sha256(pin!!))
        assertEquals("true", db.settingsDao().getSettingValue("entry_pin_enabled"))
        assertEquals("2:0", db.settingsDao().getSettingValue("pin_attempt_state"))
        assertEquals("KEEP_MARKER",
            db.settingsDao().getSettingValue("lanu_ota_probe_private_marker"))
        assertEquals(identity.code, db.userProfileDao().getUserProfile()?.phoneNumber)
        assertEquals("OTA Owner", db.userProfileDao().getUserProfile()?.displayName)
        assertTrue(db.contactDao().getContactByPhone("LANU-OTA-PEER")!!.isBlocked)
        assertEquals(4, db.conversationDao().getConversationById(conversationId)?.unreadCount)
        val message = db.messageDao().getMessage(messageId)
        assertNotNull("Queued ciphertext disappeared", message)
        assertEquals("PENDING", message!!.status)
        assertEquals(conversationId, message.conversationId)
        assertEquals(sentinels.getString("cipher_digest", null), sha256(message.content))
        assertEquals("OTA encrypted test message", PeerMessageCrypto.decrypt(
            identity, identity.id, identity.publicKey, messageId, identity.id, message.content
        ))
        assertEquals("42", db.calculationHistoryDao().getAllHistory().first()
            .single { it.id == 44001L }.result)
        assertEquals(5, db.callLogDao().getAllCallLogs().first()
            .single { it.id == 55001L }.durationSeconds)
        // Deliberately leave the installed app and data intact for debugging.
        // CI discards the entire ephemeral emulator VM, never the user database.
    }
}
