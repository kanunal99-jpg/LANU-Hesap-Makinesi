package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.*
import com.example.core.messaging.*
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerTrustAtomicityTest {
    private lateinit var db: AppDatabase
    private lateinit var own: MessagingIdentity
    private lateinit var peer: MessagingIdentity
    private lateinit var relay: MessagingRelay

    private fun peerJson() = JSONObject()
        .put("id", peer.id).put("code", peer.code).put("nonce", peer.nonce)
        .put("publicKey", peer.publicKey).put("mutual", true)
        .put("codeAtAdd", peer.code)

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        own = UserCodes.create()
        peer = UserCodes.create()
        relay = object : MessagingRelay {
            override val configured = true
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject =
                when (action) {
                    "register" -> JSONObject().put("code", own.code).put("nonce", own.nonce)
                    "addContact" -> JSONObject().put("peer", peerJson())
                    "sync" -> JSONObject().put("contacts", JSONArray().put(peerJson()))
                        .put("events", JSONArray())
                    else -> error("Unexpected relay action: $action")
                }
        }
    }
    @After fun close() { db.close() }

    private fun repository(
        settings: SettingsDao = db.settingsDao(),
        contacts: ContactDao = db.contactDao()
    ): CommunicationRepository = CommunicationRepository(
        contacts, db.conversationDao(), db.messageDao(), settings,
        db.userProfileDao(), db.callLogDao(), object : MessagingIdentityStore {
            override fun read() = own
            override fun save(identity: MessagingIdentity) {
                assertEquals(own.id, identity.id)
                own = identity
            }
        }, relay, db
    )

    @Test fun `failed alias insert rolls back every saved trust mapping and retry succeeds`() = runBlocking {
        val broken = object : SettingsDao by db.settingsDao() {
            override suspend fun insertSetting(setting: SettingsEntity) {
                if (setting.key == "lanu_peer_code_${peer.code}") {
                    throw IllegalStateException("Injected failure on alias")
                }
                db.settingsDao().insertSetting(setting)
            }
        }
        try {
            repository(settings = broken).synchronize()
            fail("Key alias write failure must roll back")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Injected failure"))
        }
        assertNull(db.settingsDao().getSettingValue("lanu_peer_id_${peer.id}"))
        assertNull(db.settingsDao().getSettingValue("lanu_peer_code_${peer.code}"))
        val convId = UserCodes.conversationId(own.id, peer.id)
        assertNull(db.settingsDao().getSettingValue("lanu_peer_conv_$convId"))

        repository().synchronize()
        assertNotNull(db.settingsDao().getSettingValue("lanu_peer_id_${peer.id}"))
        assertNotNull(db.settingsDao().getSettingValue("lanu_peer_code_${peer.code}"))
        assertNotNull(db.settingsDao().getSettingValue("lanu_peer_conv_$convId"))
    }

    @Test fun `contact write failure rolls back trusted settings and preserves original contact`() = runBlocking {
        db.contactDao().insertContact(ContactEntity(peer.code, "Previous contact"))
        val broken = object : ContactDao by db.contactDao() {
            override suspend fun insertContact(contact: ContactEntity) {
                throw IllegalStateException("Injected contact persistence failure")
            }
        }
        try {
            repository(contacts = broken).synchronize()
            fail("A failed contact update must not leave trusted peer aliases")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Injected contact"))
        }
        assertEquals("Previous contact", db.contactDao().getContactByPhone(peer.code)?.name)
        assertNull(db.settingsDao().getSettingValue("lanu_peer_id_${peer.id}"))
        assertNull(db.settingsDao().getSettingValue("lanu_peer_code_${peer.code}"))

        repository().synchronize()
        assertEquals("Karşılıklı kayıtlı", db.contactDao().getContactByPhone(peer.code)?.statusText)
        assertNotNull(db.settingsDao().getSettingValue("lanu_peer_id_${peer.id}"))
    }
}
