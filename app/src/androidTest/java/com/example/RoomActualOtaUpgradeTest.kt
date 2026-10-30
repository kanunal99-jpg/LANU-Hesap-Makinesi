package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Build
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.*
import com.example.core.messaging.EncryptedMessagingIdentityStore
import com.example.core.messaging.MessagingIdentity
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
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.KeyFactory
import java.security.Signature
import java.lang.reflect.Modifier
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
        // The normal Android test suite does not run the destructive seed
        // phase. A CI-flagged invocation MUST fail, not silently skip, if
        // the runtime is a physical device or the requested phase mismatches.
        assumeTrue("Not part of normal Android test suite",
            args.getString("lanu_ota_ci") == "isolated-emulator-v1")
        check(emulator && args.getString("lanu_ota_phase") == phase) {
            "OTA probes require an isolated emulator and the exact phase"
        }
        val expected = args.getString("lanu_expected_version")?.toLongOrNull()
            ?: error("OTA expected version not provided")
        @Suppress("DEPRECATION")
        val actual = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertEquals("Wrong installed APK; upgrade experiment is invalid", expected, actual)
    }

    // Fixed, non-sensitive progress markers; the shell harness whitelists them.
    // No PIN, key material, database content or stack trace enters public logs.
    private fun checkpoint(stage: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putString("lanu_ota_checkpoint", stage) })
    }

    private fun confirmPhase(phase: String) {
        // An AndroidJUnitRunner assumption/skip can still end in "OK (1 test)".
        // Emit an explicit instrumentation-status marker ONLY after every data
        // preservation assertion succeeds, so CI cannot accept an empty skip.
        InstrumentationRegistry.getInstrumentation().sendStatus(0,
            Bundle().apply { putString("lanu_ota_verified_phase", phase) })
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * Test-only v828 fixture generator. The published R8 APK inlines and removes
     * UserCodes.create() from its ABI; separately compiled instrumentation must
     * not call that missing method. Match the app's P-256 / URL-safe Base64 /
     * LANU-code algorithm exactly, then let the REAL encrypted identity store
     * validate and persist it using the installed version's Keystore.
     */
    private data class SeedIdentity(
        val record: MessagingIdentity,
        val id: String,
        val code: String,
        val nonce: String,
        val publicKey: String,
        val privateKey: String
    )

    private fun makeLegacyCompatibleIdentity(): SeedIdentity {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        fun encode(bytes: ByteArray): String = Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val publicKey = encode(keyPair.public.encoded)
        val privateKey = encode(keyPair.private.encoded)
        val nonce = encode(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$publicKey:$nonce".toByteArray(Charsets.UTF_8))
        var buffer = 0
        var bits = 0
        val chars = StringBuilder()
        for (byte in digest) {
            buffer = (buffer shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 5 && chars.length < 20) {
                bits -= 5
                chars.append(alphabet[(buffer ushr bits) and 31])
            }
            if (chars.length == 20) break
        }
        val code = "LANU-" + chars.toString().chunked(4).joinToString("-")
        val id = sha256(publicKey)
        return SeedIdentity(MessagingIdentity(id, code, nonce, publicKey, privateKey),
            id, code, nonce, publicKey, privateKey)
    }

    private fun fixtureBase64(bytes: ByteArray): String = Base64.encodeToString(
        bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )

    /**
     * Independent JCA fixture for the exact production PIN record format.
     * Published v828 R8 does not guarantee the Kotlin singleton ABI.
     * The regular API34 Android test checks parity with the real PIN codec.
     */
    private fun makeLegacyCompatiblePinRecord(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(pin.toCharArray(), salt, 210_000, 256)
        val hash = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return "pbkdf2-sha256:210000:" + fixtureBase64(salt) + ":" + fixtureBase64(hash)
    }

    /**
     * Independent ECDH + AES/GCM fixture in LANU v2 ciphertext format.
     * The ordinary Android test verifies decryptability through production
     * PeerMessageCrypto; actual stored bytes remain asserted after OTA.
     */
    private fun makeLegacyCompatibleCipher(
        identity: SeedIdentity, messageId: String, plaintext: String
    ): String {
        fun decode(input: String): ByteArray =
            Base64.decode(input, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val keys = KeyFactory.getInstance("EC")
        val privateKey = keys.generatePrivate(PKCS8EncodedKeySpec(decode(identity.privateKey)))
        val publicKey = keys.generatePublic(X509EncodedKeySpec(decode(identity.publicKey)))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }
        val conversationHash = sha256(identity.id + ":" + identity.id)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(shared)
        digest.update(("LANU-v1:" + conversationHash).toByteArray(Charsets.UTF_8))
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(digest.digest(), "AES"), GCMParameterSpec(128, iv))
        aes.updateAAD((messageId + ":" + identity.id + ":" + identity.id).toByteArray(Charsets.UTF_8))
        return "lanu2:" + fixtureBase64(iv + aes.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun syntheticIdentityFixtureMatchesProductionKeyPairVerifier() {
        // Debug instrumentation validates fixture parity against the real
        // Kotlin objects. Release R8 may legitimately erase their INSTANCE
        // fields: this auxiliary parity check is debug-only, while the real
        // signed OTA seed/verify tests remain mandatory on release.
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        assumeTrue("Fixture parity is required on the debug runtime, not R8 release", isDebuggable)
        val identity = makeLegacyCompatibleIdentity()
        assertTrue(UserCodes.isConsistentIdentity(identity.record))
        val digest = sha256(identity.privateKey + identity.publicKey +
            identity.id + identity.code + identity.nonce)
        val recovered = recoverIdentityForProof(identity.record, digest)
        assertTrue("Real EC key pair must survive reflection-based read", verifyFixtureKeyPair(recovered))
        assertEquals(identity.code, recovered.code)
        assertEquals(identity.id, recovered.id)
        val pin = makeLegacyCompatiblePinRecord("739188")
        assertTrue("PIN fixture must match production codec", PinCredentialCodec.verify("739188", pin))
        val ciphertext = makeLegacyCompatibleCipher(identity, messageId, "OTA encrypted test message")
        assertEquals("OTA encrypted test message", PeerMessageCrypto.decrypt(
            identity.record, identity.id, identity.publicKey, messageId, identity.id, ciphertext
        ))
    }

    /** Works against v828 R8 and current R8 without statically linking constructors. */
    private fun openCompatibleIdentityStore(): EncryptedMessagingIdentityStore = try {
        val constructors = EncryptedMessagingIdentityStore::class.java.declaredConstructors
        val ctor = constructors.singleOrNull { candidate ->
            candidate.parameterTypes.size == 2 &&
                candidate.parameterTypes[0] == Context::class.java &&
                !candidate.parameterTypes[1].isPrimitive
        } ?: constructors.singleOrNull { candidate ->
            candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0] == Context::class.java
        } ?: error("Published identity-store constructor ABI unsupported")
        @Suppress("UNCHECKED_CAST")
        (if (ctor.parameterTypes.size == 2) ctor.newInstance(context, null)
         else ctor.newInstance(context)) as EncryptedMessagingIdentityStore
    } catch (failure: ReflectiveOperationException) {
        throw IllegalStateException("Published identity-store ABI probe failed", failure)
    }

    /**
     * R8 removes MessagingIdentity getters and obfuscates its five String fields.
     * Read them by type, never by obfuscated names or declaration order. The
     * exact seeded five-field digest resolves the unique correct permutation.
     * No private values are logged or copied into the instrumentation bundle.
     */
    private fun recoverIdentityForProof(record: MessagingIdentity, expectedDigest: String): SeedIdentity {
        val fields = record.javaClass.declaredFields
            .filter { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }
        check(fields.size == 5) { "Unexpected encrypted identity data ABI" }
        val values = fields.map { field ->
            field.isAccessible = true
            field.get(record) as String
        }
        var matched: SeedIdentity? = null
        for (id in values.indices) for (code in values.indices) for (nonce in values.indices)
            for (pub in values.indices) for (priv in values.indices) {
                if (setOf(id, code, nonce, pub, priv).size != 5) continue
                val candidate = SeedIdentity(record, values[id], values[code],
                    values[nonce], values[pub], values[priv])
                val digest = sha256(candidate.privateKey + candidate.publicKey +
                    candidate.id + candidate.code + candidate.nonce)
                if (MessageDigest.isEqual(
                    digest.toByteArray(Charsets.US_ASCII),
                    expectedDigest.toByteArray(Charsets.US_ASCII)
                )) {
                    check(matched == null) { "Ambiguous persisted identity fields" }
                    matched = candidate
                }
            }
        return checkNotNull(matched) { "Encrypted account identity was not preserved" }
    }

    private fun verifyFixtureKeyPair(identity: SeedIdentity): Boolean = runCatching {
        if (identity.id != sha256(identity.publicKey)) return@runCatching false
        fun decode(v: String) = Base64.decode(
            v, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val factory = KeyFactory.getInstance("EC")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(decode(identity.privateKey)))
        val publicKey = factory.generatePublic(X509EncodedKeySpec(decode(identity.publicKey)))
        val challenge = "LANU-signed-OTA-identity-keypair-v1".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(challenge)
            sign()
        }
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(challenge)
            verify(signature)
        }
    }.getOrDefault(false)

    private fun decryptLegacyCompatibleCipher(
        identity: SeedIdentity, messageId: String, content: String
    ): String {
        require(content.startsWith("lanu2:"))
        fun decode(v: String) = Base64.decode(
            v, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val bytes = decode(content.removePrefix("lanu2:"))
        require(bytes.size >= 28)
        val factory = KeyFactory.getInstance("EC")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(decode(identity.privateKey)))
        val publicKey = factory.generatePublic(X509EncodedKeySpec(decode(identity.publicKey)))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(shared)
        hash.update(("LANU-v1:" + sha256(identity.id + ":" + identity.id))
            .toByteArray(Charsets.UTF_8))
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(hash.digest(), "AES"),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        aes.updateAAD((messageId + ":" + identity.id + ":" + identity.id)
            .toByteArray(Charsets.UTF_8))
        return String(aes.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private val sentinels: android.content.SharedPreferences
        get() = context.getSharedPreferences("lanu_ci_ota_preservation_probe", Context.MODE_PRIVATE)

    private val messageId = "lanu-ci-ota-pending-1"
    private val conversationId = "lanu-ci-ota-conversation-1"

    @Test
    fun seedPreviouslyInstalledStableRelease() = runBlocking {
        gate("seed")
        checkpoint("gate")
        assertNull("Never overwrite a previous probe", sentinels.getString("probe", null))
        checkpoint("prefs-empty")
        // The published v828 target is R8-minified. Separately compiled tests
        // must not link Kotlin's synthetic default-argument constructor, which
        // is not a guaranteed ABI of the previously published APK.
        val identityStore = openCompatibleIdentityStore()
        checkpoint("identity-store-created")
        assertNull("Seed only a fresh isolated emulator", identityStore.read())
        checkpoint("identity-store-empty")
        checkpoint("fresh-identity")
        val identity = makeLegacyCompatibleIdentity()
        checkpoint("identity-generated")
        identityStore.save(identity.record)
        checkpoint("identity-saved")
        val pin = makeLegacyCompatiblePinRecord("739188")
        checkpoint("pin-generated")
        val cipher = makeLegacyCompatibleCipher(identity, messageId, "OTA encrypted test message")
        checkpoint("cipher-generated")
        checkpoint("identity-crypto")
        val database = AppDatabase.getDatabase(context)
        checkpoint("database-open")
        assertNull(database.settingsDao().getSettingValue("pin_hash"))
        database.run {
            settingsDao().insertSetting(SettingsEntity("pin_hash", pin))
            settingsDao().insertSetting(SettingsEntity("entry_pin_enabled", "true"))
            settingsDao().insertSetting(SettingsEntity("pin_attempt_state", "2:0"))
            settingsDao().insertSetting(SettingsEntity("lanu_ota_probe_private_marker", "KEEP_MARKER"))
            userProfileDao().insertProfile(UserProfileEntity(identity.code, "OTA Owner", avatarUrl = null, registeredAt = 41L))
            contactDao().insertContact(ContactEntity("LANU-OTA-PEER", "OTA Contact", isBlocked = true,
                avatarUrl = null, statusText = "Karşılıklı kayıt bekleniyor"))
            conversationDao().insertConversation(ConversationEntity(
                conversationId, "LANU-OTA-PEER", "OTA Contact", unreadCount = 4,
                lastMessageText = "Şifreli mesaj", lastMessageTime = 42L, isGroup = false
            ))
            messageDao().insertMessage(MessageEntity(
                messageId, conversationId, identity.id, "OTA Owner",
                cipher, mediaUrl = null, timestamp = 43L, status = "PENDING", reaction = null
            ))
            calculationHistoryDao().insertHistory(CalculationHistoryEntity(
                id = 44001L, expression = "40+2", result = "42", timestamp = 44L
            ))
            callLogDao().insertCallLog(CallLogEntity(
                id = 55001L, partnerName = "OTA Contact", partnerPhone = "LANU-OTA-PEER",
                isVideo = false, durationSeconds = 5, isOutgoing = true, timestamp = 45L
            ))
        }
        checkpoint("seven-tables-written")
        val persisted = sentinels.edit()
            .putString("probe", "prepared")
            .putString("identity_digest", sha256(identity.privateKey + identity.publicKey + identity.id + identity.code + identity.nonce))
            .putString("pin_digest", sha256(pin))
            .putString("cipher_digest", sha256(cipher))
            .commit()
        assertTrue("The pre-update sentinel must be durably saved", persisted)
        checkpoint("sentinel-written")
        assertEquals(messageId, database.messageDao().getPendingMessages(
            now = System.currentTimeMillis(), limit = 200).single().id)
        checkpoint("pending-verified")
        confirmPhase("seed")
    }

    @Test
    fun verifySameAccountAndAllSevenTablesAfterRealApkReplacement() = runBlocking {
        gate("verify")
        assertEquals("The previous APK's private prefs must survive install -r",
            "prepared", sentinels.getString("probe", null))
        val stored = openCompatibleIdentityStore().read()
        assertNotNull("Keystore-backed account lost after APK install -r", stored)
        val expectedDigest = sentinels.getString("identity_digest", null)
        assertNotNull("OTA account digest sentinel was lost", expectedDigest)
        val identity = recoverIdentityForProof(stored!!, expectedDigest!!)
        assertTrue("Recovered private key must verify the stored public identity",
            verifyFixtureKeyPair(identity))
        assertEquals("Keystore account changed or reset", expectedDigest,
            sha256(identity.privateKey + identity.publicKey + identity.id + identity.code + identity.nonce))
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
        assertEquals("OTA encrypted test message",
            decryptLegacyCompatibleCipher(identity, messageId, message.content))
        assertEquals("42", db.calculationHistoryDao().getAllHistory().first()
            .single { it.id == 44001L }.result)
        assertEquals(5, db.callLogDao().getAllCallLogs().first()
            .single { it.id == 55001L }.durationSeconds)
        confirmPhase("verify")
        // Deliberately leave the installed app and data intact for debugging.
        // CI discards the entire ephemeral emulator VM, never the user database.
    }
}
