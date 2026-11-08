package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.database.*
import com.example.core.messaging.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RatchetRootRegistryRuntimeTest {
    private class Fixture:AutoCloseable {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val own=UserCodes.create();val peer=UserCodes.create()
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val namespaces=mutableListOf<String>()
        suspend fun stage():Pair<RatchetRootRegistry.Manifest,JSONObject> {
            val manifest=RatchetRootRegistry.fresh(own,peer.id);namespaces.add(manifest.namespace)
            val keys=RatchetSecrets.open(context,manifest.namespace,create=true)
            try {
                val service=RatchetSessionService(db,RatchetTransactionStore(db,
                    RatchetFileJournal(context.noBackupFilesDir,manifest.namespace),keys,manifest.stateKey),keys)
                return manifest to service.prepare(own,peer.id,persistPublic=false)
            } finally {keys.close()}
        }
        override fun close() {
            db.close()
            val keyStore=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            for(ns in namespaces) {
                keyStore.deleteEntry("lanu_ratchet_wrap_v1_$ns")
                namespacesFiles(ns)
                namespacesFiles(RatchetTransactionStore.digest("LANU-KEY-WRAP|$ns"))
            }
            namespacesFiles(RatchetRootRegistry.recordId(own.id,peer.id))
        }
        private fun namespacesFiles(id:String) {
            for(suffix in listOf("",".bak",".new")) File(context.noBackupFilesDir,"lanu-ratchet-v1/$id.journal$suffix").delete()
        }
    }
    @Test fun selectorRecoversEveryCommitBoundaryAndRejectsRoomRollback()=runBlocking {
        for(cut in RatchetRootRegistry.Checkpoint.values()) Fixture().use {f ->
            val (manifest,bundle)=f.stage()
            val registry=RatchetRootRegistry(f.context,f.db)
            assertTrue(runCatching {registry.select(f.own,manifest,bundle,null,checkpoint={if(it==cut) error("fixture cut")})}.isFailure)
            assertEquals(manifest.encoded,RatchetRootRegistry(f.context,f.db).current(f.own,f.peer.id)!!.encoded)
            assertEquals(bundle.toString(),f.db.settingsDao().getSettingValue("lanu_dr_public_bundle_${f.peer.id}"))
            f.db.settingsDao().deleteSetting(RatchetRootRegistry.pointerKey(f.peer.id))
            assertTrue(runCatching {RatchetRootRegistry(f.context,f.db).current(f.own,f.peer.id)}.isFailure)
            val oldCipher=f.db.settingsDao().getSettingValue(manifest.stateKey)
            assertNotNull(oldCipher)
            val selector=File(f.context.noBackupFilesDir,"lanu-ratchet-v1/${RatchetRootRegistry.recordId(f.own.id,f.peer.id)}.journal")
            selector.writeText("damaged public selector")
            assertTrue(runCatching {RatchetRootRegistry(f.context,f.db).current(f.own,f.peer.id)}.isFailure)
            val (fresh,freshBundle)=f.stage()
            RatchetRootRegistry(f.context,f.db).select(f.own,fresh,freshBundle,null,explicitRecovery=true)
            assertEquals(fresh.encoded,RatchetRootRegistry(f.context,f.db).current(f.own,f.peer.id)!!.encoded)
            assertEquals(oldCipher,f.db.settingsDao().getSettingValue(manifest.stateKey))
        }
    }
    @Test fun missingAnchorAndMissingKeystoreNeverCreateReplacementOrEraseHistory()=runBlocking {
        Fixture().use {f ->
            val coordinator=RatchetCoordinator(f.context,f.db)
            coordinator.session(f.own,f.peer.id,explicitCreate=true)
            val encoded=f.db.settingsDao().getSettingValue(RatchetRootRegistry.pointerKey(f.peer.id))!!
            val manifest=RatchetRootRegistry.parse(f.own,f.peer.id,encoded);f.namespaces.add(manifest.namespace)
            val before=f.db.settingsDao().getSettingValue(manifest.stateKey)
            val file=File(f.context.noBackupFilesDir,"lanu-ratchet-v1/${RatchetRootRegistry.recordId(f.own.id,f.peer.id)}.journal")
            val bytes=file.readBytes();assertTrue(file.delete())
            assertTrue(runCatching {RatchetCoordinator(f.context,f.db).session(f.own,f.peer.id,explicitCreate=true)}.isFailure)
            file.writeBytes(bytes)
            KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry("lanu_ratchet_wrap_v1_${manifest.namespace}")}
            assertTrue(runCatching {RatchetCoordinator(f.context,f.db).session(f.own,f.peer.id,explicitCreate=true)}.isFailure)
            assertEquals(setOf(f.peer.id),RatchetCoordinator(f.context,f.db).recoverExisting(f.own))
            assertEquals(before,f.db.settingsDao().getSettingValue(manifest.stateKey))
            assertEquals(encoded,f.db.settingsDao().getSettingValue(RatchetRootRegistry.pointerKey(f.peer.id)))
        }
    }
    @Test fun retiredGenerationIsFilteredBeforeOutboxLimitAndPortableBackupExcludesEveryPrivateRoot()=runBlocking {
        Fixture().use {f ->
            val conversation=UserCodes.conversationId(f.own.id,f.peer.id)
            f.db.conversationDao().insertConversation(ConversationEntity(conversation,f.peer.code,"fixture"))
            val active="b".repeat(64)
            repeat(210) {n ->
                val id=UUID.randomUUID().toString()
                f.db.messageDao().insertMessage(MessageEntity(id,conversation,f.own.id,"fixture","lanu3-local:fixture",timestamp=n.toLong(),status="WAITING"))
                f.db.settingsDao().insertSetting(SettingsEntity("lanu_dr_wire_epoch_$id","a".repeat(64)))
            }
            repeat(20) {n ->
                f.db.messageDao().insertMessage(MessageEntity(UUID.randomUUID().toString(),conversation,f.own.id,"fixture","lanu2:legacyfixture",timestamp=n.toLong(),status="WAITING"))
            }
            val fresh=UUID.randomUUID().toString()
            f.db.messageDao().insertMessage(MessageEntity(fresh,conversation,f.own.id,"fixture","lanu3-local:fixture",timestamp=999,status="PENDING"))
            f.db.settingsDao().insertSetting(SettingsEntity("lanu_dr_wire_epoch_$fresh",active))
            f.db.settingsDao().insertSetting(SettingsEntity("lanu_dr_epoch_$conversation",active))
            assertEquals(listOf(fresh),f.db.messageDao().getPendingMessages(now=1000,limit=5).map {it.id})
            f.db.settingsDao().insertSetting(SettingsEntity("lanu_dr_private_state_${"f".repeat(64)}","private fixture"))
            val snapshot=FullDataBackup.capture(f.db.openHelper.writableDatabase,f.own)
            val rows=snapshot.getJSONObject("tables").getJSONArray("app_settings")
            val settings=(0 until rows.length()).associate {rows.getJSONArray(it).getString(0) to rows.getJSONArray(it).getString(1)}
            assertFalse(settings.keys.any {it.startsWith("lanu_dr_private_state_")})
            assertEquals(active,settings["lanu_dr_wire_epoch_$fresh"])
            assertEquals(231,f.db.messageDao().countModernEncryptedMessages())
        }
    }
}
