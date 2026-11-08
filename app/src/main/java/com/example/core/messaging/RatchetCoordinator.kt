package com.example.core.messaging

import android.content.Context
import androidx.room.withTransaction
import com.example.core.database.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Per-peer device roots; no startup/background key creation or lost-key replacement. */
class RatchetCoordinator(private val context:Context,private val database:AppDatabase) {
    private val mutex=Mutex()
    private val registry=RatchetRootRegistry(context,database)
    private class Loaded(val manifest:RatchetRootRegistry.Manifest,val keys:RatchetSecrets,
        val persistence:RatchetTransactionStore,val service:RatchetSessionService)
    private val roots=mutableMapOf<String,Loaded>()
    private suspend fun load(own:MessagingIdentity,manifest:RatchetRootRegistry.Manifest,create:Boolean=false):Loaded {
        require(manifest.owner==own.id)
        roots[manifest.namespace]?.let {it.persistence.recover();return it}
        val keys=RatchetSecrets.open(context,manifest.namespace,create)
        try {
            val store=RatchetTransactionStore(database,RatchetFileJournal(context.noBackupFilesDir,manifest.namespace),keys,manifest.stateKey)
            store.recover()
            return Loaded(manifest,keys,store,RatchetSessionService(database,store,keys)).also {roots[manifest.namespace]=it}
        } catch(error:Throwable) {keys.close();throw error}
    }
    private suspend fun setting(key:String,value:String)=database.settingsDao().insertSetting(SettingsEntity(key,value))
    private suspend fun freshInitial(own:MessagingIdentity,peer:String,repair:Boolean):RatchetSessionService {
        check(database.settingsDao().getSettingValue("lanu_dr_pin_$peer")==null &&
            database.settingsDao().getSettingValue("lanu_dr_epoch_${UserCodes.conversationId(own.id,peer)}")==null) {
            "Etkin oturum için iki taraflı yenileme gerekir."
        }
        check(!database.openHelper.writableDatabase.query(
            "SELECT 1 FROM messages WHERE conversationId=? AND content LIKE 'lanu3-local:%' LIMIT 1",
            arrayOf(UserCodes.conversationId(own.id,peer))).use {it.moveToFirst()}) {
            "Mevcut güvenli mesajlar var; iki taraflı yenileme gerekir."
        }
        // A readable former root already bound to a remote bundle cannot be
        // treated as a never-active initial setup, even if public Room pins
        // were rolled back. Missing private material is handled only by an
        // explicitly two-owner-approved renewal after a ready relay proof.
        if(repair) {
            val previous=database.settingsDao().getSettingValue(RatchetRootRegistry.pointerKey(peer))
            if(previous!=null) {
                val manifest=RatchetRootRegistry.parse(own,peer,previous)
                val deviceKeys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
                if(deviceKeys.containsAlias("lanu_ratchet_wrap_v1_${manifest.namespace}")) {
                    val old=load(own,manifest)
                    val cipher=checkNotNull(database.settingsDao().getSettingValue(manifest.stateKey))
                    val entry=RatchetProtocol.parse(old.keys.open("state",cipher),2*1024*1024).getJSONObject("peers").getJSONObject(peer)
                    check(!entry.has("remote") && entry.getJSONObject("sessions").length()==0) { "Önceden etkinleşmiş oturum için iki taraflı yenileme gerekir." }
                }
                // An explicitly confirmed, server-proven UNUSED initial setup
                // may get a new namespace after Keystore loss. No old bytes/key
                // alias are replaced; active/history-backed setups failed above.

            }
        }
        val saved=if(repair)null else database.settingsDao().getSettingValue(RatchetRootRegistry.initialKey(peer))
        val manifest=saved?.let {RatchetRootRegistry.parse(own,peer,it)} ?: RatchetRootRegistry.fresh(own,peer).also {
            setting(RatchetRootRegistry.initialKey(peer),it.encoded)
        }
        val exists=database.settingsDao().getSettingValue(manifest.stateKey)!=null
        val loaded=load(own,manifest,create=!exists)
        val bundle=loaded.service.prepare(own,peer,persistPublic=false)
        registry.select(own,manifest,bundle,null,allowMissingAnchor=repair)
        return loaded.service
    }
    internal suspend fun session(identity:MessagingIdentity,peer:String,explicitCreate:Boolean=false):RatchetSessionService=withContext(Dispatchers.IO) { mutex.withLock {
        val selected=registry.current(identity,peer)
        if(selected!=null) return@withLock load(identity,selected).service
        check(explicitCreate && database.settingsDao().getSettingValue("lanu_dr_pin_$peer")==null &&
            database.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY)==null) {
            "Bu cihazda oturum anahtarı yok. Geçmiş korundu; açık oturum yenilemesi gerekiyor."
        }
        freshInitial(identity,peer,repair=false)
    } }
    /** Only invoked after the owner explicitly selected recovery and the relay
     * proves that the initial pair has never become active. No old key/file is erased. */
    internal suspend fun repairInitial(identity:MessagingIdentity,peer:String):RatchetSessionService=withContext(Dispatchers.IO) { mutex.withLock {
        freshInitial(identity,peer,repair=true)
    } }
    internal suspend fun recoverExisting(identity:MessagingIdentity):Set<String> {
        val unavailable=mutableSetOf<String>()
        for(row in database.settingsDao().getRatchetPublicBundles()) {
            val peer=row.key.removePrefix("lanu_dr_public_bundle_")
            try {session(identity,peer)}
            catch(error:CancellationException) {throw error}
            catch(_:Exception) {unavailable.add(peer) /* Never block unrelated inboxes or replace missing keys. */ }
        }
        return unavailable
    }
    /** Candidate is journaled in a NEW namespace, separate from the active root. */
    internal suspend fun prepareRenewal(own:MessagingIdentity,peer:String,resetId:String,previous:String):JSONObject=withContext(Dispatchers.IO) { mutex.withLock {
        val key=RatchetRootRegistry.resetKey(peer)
        val saved=database.settingsDao().getSettingValue(key)?.let {RatchetProtocol.parse(it,8192)}
        if(saved!=null && saved.getString("resetId")==resetId && saved.getString("previousEpoch")==previous) {
            val manifest=RatchetRootRegistry.parse(own,peer,saved.getString("manifest"))
            load(own,manifest) // Missing candidate key never regenerates silently.
            return@withLock saved
        }
        val manifest=RatchetRootRegistry.fresh(own,peer)
        // The random namespace is independent of every existing device key.
        // Complete candidate metadata is durable before any network vote.
        val loaded=load(own,manifest,create=true)
        val bundle=loaded.service.prepare(own,peer,persistPublic=false)
        val proof=UserCodes.sign(own,RatchetProtocol.resetTranscript(own.id,peer,resetId,previous,bundle))
        val candidate=JSONObject().put("resetId",resetId).put("previousEpoch",previous).put("manifest",manifest.encoded)
            .put("bundle",bundle).put("proof",proof)
        setting(key,candidate.toString())
        candidate
    } }
    internal suspend fun finishRenewal(own:MessagingIdentity,peer:String,peerKey:String,receipt:JSONObject,current:JSONObject):Boolean=withContext(Dispatchers.IO) { mutex.withLock {
        val saved=database.settingsDao().getSettingValue(RatchetRootRegistry.resetKey(peer))?.let {RatchetProtocol.parse(it,8192)} ?: return@withLock false
        val id=saved.getString("resetId")
        if(receipt.getString("id")!=id) return@withLock false
        val (local,remote)=RatchetProtocol.verifyRenewal(own,peer,peerKey,receipt)
        check(receipt.getString("previous_epoch")==saved.getString("previousEpoch") &&
            RatchetProtocol.transcript(local)==RatchetProtocol.transcript(saved.getJSONObject("bundle")) &&
            local.getString("signature")==saved.getJSONObject("bundle").getString("signature")) { "Yenileme onayı bu cihazın adayına ait değil." }
        val epoch=RatchetProtocol.verifyCurrentPair(own,peer,peerKey,current)
        check(epoch==receipt.getString("new_epoch")) { "Oturum daha sonra değişmiş; açık yenileme gerekiyor." }
        val manifest=RatchetRootRegistry.parse(own,peer,saved.getString("manifest"))
        val loaded=load(own,manifest)
        loaded.service.pin(own,peer,peerKey,remote,persistPin=false)
        registry.select(own,manifest,local,epoch,allowMissingAnchor=true)
        // Only a completed switch can clear the public request. Old private
        // state rows, old journals, wrapping keys and pending ciphertext stay intact.
        database.settingsDao().deleteSetting(RatchetRootRegistry.resetKey(peer))
        for(old in roots.values.filter {it.manifest.owner==own.id && it.manifest.peer==peer && it.manifest.namespace!=manifest.namespace}) {
            old.keys.close();roots.remove(old.manifest.namespace)
        }
        true
    } }
    internal suspend fun candidate(peer:String):JSONObject?=database.settingsDao().getSettingValue(RatchetRootRegistry.resetKey(peer))?.let {RatchetProtocol.parse(it,8192)}
    internal suspend fun cancelCandidate(peer:String,id:String) {
        val existing=candidate(peer) ?: return
        if(existing.getString("resetId")==id) database.settingsDao().deleteSetting(RatchetRootRegistry.resetKey(peer))
    }
}
