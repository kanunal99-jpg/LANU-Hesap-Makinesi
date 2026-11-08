package com.example.core.messaging

import android.content.Context
import androidx.room.withTransaction
import com.example.core.database.*
import org.json.JSONObject
import java.util.UUID

/** Signed, no-backup selection of a device key namespace. No private key in a record. */
internal class RatchetRootRegistry(private val context:Context,private val database:AppDatabase) {
    enum class Checkpoint { PREPARED, ROOM_COMMITTED, FINALIZED }
    class Manifest(val owner:String,val peer:String,val namespace:String,val encoded:String) {
        val stateKey get()="lanu_dr_private_state_$namespace"
        override fun toString()="RatchetDeviceManifest(redacted)"
    }
    companion object {
        private val HEX=Regex("[a-f0-9]{64}")
        fun pointerKey(peer:String)="lanu_dr_device_$peer"
        fun initialKey(peer:String)="lanu_dr_setup_$peer"
        fun resetKey(peer:String)="lanu_dr_reset_$peer"
        fun recordId(owner:String,peer:String)=RatchetTransactionStore.digest("LANU-DR3-ROOT-REGISTRY|$owner|$peer")
        fun fresh(own:MessagingIdentity,peer:String):Manifest {
            require(HEX.matches(peer) && peer!=own.id)
            val namespace=RatchetTransactionStore.digest("LANU-DR3-DEVICE|${own.id}|$peer|${UUID.randomUUID()}")
            val raw=JSONObject().put("v",1).put("owner",own.id).put("peer",peer).put("namespace",namespace).toString()
            return Manifest(own.id,peer,namespace,UserCodes.encode(raw.toByteArray(Charsets.UTF_8)))
        }
        fun parse(own:MessagingIdentity,peer:String,encoded:String):Manifest {
            require(encoded.length in 100..1024)
            val json=RatchetProtocol.parse(String(UserCodes.decode(encoded),Charsets.UTF_8),768)
            require(json.keys().asSequence().toSet()==setOf("v","owner","peer","namespace") && json.getInt("v")==1 &&
                json.getString("owner")==own.id && json.getString("peer")==peer && HEX.matches(json.getString("namespace")))
            return Manifest(own.id,peer,json.getString("namespace"),encoded)
        }
    }
    private fun location(own:MessagingIdentity,peer:String)=RatchetFileJournal(context.noBackupFilesDir,recordId(own.id,peer))
    private fun signed(own:MessagingIdentity,payload:JSONObject):String {
        val text=payload.toString()
        return JSONObject().put("payload",text).put("signature",UserCodes.sign(own,"LANU-DR3-ROOT-SELECTION-v1\n$text")).toString()
    }
    private fun read(own:MessagingIdentity,peer:String):JSONObject? {
        val saved=location(own,peer).read() ?: return null
        val outer=RatchetProtocol.parse(saved,32768)
        require(outer.keys().asSequence().toSet()==setOf("payload","signature"))
        val text=outer.getString("payload")
        RatchetProtocol.verifySignature(own.publicKey,"LANU-DR3-ROOT-SELECTION-v1\n$text",outer.getString("signature"))
        return RatchetProtocol.parse(text,24576).also {
            require(it.keys().asSequence().toSet()==setOf("v","owner","peer","current","pending") && it.getInt("v")==1 &&
                it.getString("owner")==own.id && it.getString("peer")==peer)
            if(!it.isNull("current")) parse(own,peer,it.getString("current"))
        }
    }
    private fun record(own:MessagingIdentity,peer:String,current:String?,pending:JSONObject?=null)=
        JSONObject().put("v",1).put("owner",own.id).put("peer",peer)
            .put("current",current ?: JSONObject.NULL).put("pending",pending ?: JSONObject.NULL)
    private fun value(json:JSONObject,key:String):String?=if(json.isNull(key)) null else json.getString(key)
    private suspend fun validateTarget(own:MessagingIdentity,manifest:Manifest,expectedDigest:String?=null) {
        val keys=RatchetSecrets.open(context,manifest.namespace)
        try {
            RatchetTransactionStore(database,RatchetFileJournal(context.noBackupFilesDir,manifest.namespace),keys,manifest.stateKey).recover()
            val cipher=checkNotNull(database.settingsDao().getSettingValue(manifest.stateKey))
            expectedDigest?.let {check(RatchetTransactionStore.digest(cipher)==it)}
            val state=RatchetProtocol.parse(keys.open("state",cipher),2*1024*1024)
            check(state.getString("owner")==own.id && state.getJSONObject("peers").has(manifest.peer))
        } finally {keys.close()}
    }
    suspend fun current(own:MessagingIdentity,peer:String):Manifest? {
        val selected=read(own,peer)
        val stored=database.settingsDao().getSettingValue(pointerKey(peer))
        if(selected==null) {
            check(stored==null) { "Cihaz oturum seçicisi eksik; yeni anahtar üretilmedi." }
            return null
        }
        selected.optJSONObject("pending")?.let { pending ->
            val target=parse(own,peer,pending.getString("target"))
            check(stored==value(selected,"current") || stored==target.encoded) { "Cihaz oturumu geri sarılmış; kayıtlar korundu." }
            // Replaying an uncommitted selector needs the exact staged state.
            // A committed selector may already have processed later messages.
            validateTarget(own,target,if(stored!=target.encoded)pending.getString("digest") else null)
            if(stored!=target.encoded) database.withTransaction {
                check(database.settingsDao().getSettingValue(pointerKey(peer))==value(selected,"current"))
                applySelection(own,peer,pending)
            }
            check(database.settingsDao().getSettingValue(pointerKey(peer))==target.encoded)
            location(own,peer).write(signed(own,record(own,peer,target.encoded)))
            return target
        }
        check(stored==value(selected,"current")) { "Cihaz oturumu geri sarılmış; kayıtlar korundu." }
        return stored?.let {parse(own,peer,it)}
    }
    /** Explicit initial activation or independently two-owner-approved renewal only.
     * Recovery may replace a stale/missing Room selector with a NEW namespace;
     * it never selects the formerly consumed namespace to bypass rollback. */
    suspend fun select(own:MessagingIdentity,target:Manifest,bundle:JSONObject,epoch:String?,
        explicitRecovery:Boolean=false,checkpoint:(Checkpoint)->Unit={}) {
        require(target.owner==own.id)
        RatchetProtocol.verify(bundle,own.id,target.peer,own.publicKey)
        validateTarget(own,target)
        val existing=try {read(own,target.peer)} catch(error:Exception) {
            // Only the independently approved recovery path may replace a
            // damaged PUBLIC selector, and only with a different namespace.
            // Ordinary startup/current() still rejects it without changes.
            if(!explicitRecovery) throw error
            null
        }
        val stored=database.settingsDao().getSettingValue(pointerKey(target.peer))
        val previousPending=existing?.optJSONObject("pending")
        check(stored==existing?.let {value(it,"current")} || stored==previousPending?.getString("target") ||
            (explicitRecovery && target.encoded!=stored && target.encoded!=existing?.let {value(it,"current")} &&
                target.encoded!=previousPending?.getString("target"))) {
            "Cihaz oturum seçicisi doğrulanamadı."
        }
        stored?.let { parse(own,target.peer,it) }
        if(epoch==null) check(database.settingsDao().getSettingValue("lanu_dr_pin_${target.peer}")==null) {
            "Etkin güvenli oturum eski protokole düşürülemez."
        } else {
            require(HEX.matches(epoch))
            val keys=RatchetSecrets.open(context,target.namespace)
            try {
                val plain=keys.open("state",checkNotNull(database.settingsDao().getSettingValue(target.stateKey)))
                val entry=RatchetProtocol.parse(plain,2*1024*1024).getJSONObject("peers").getJSONObject(target.peer)
                check(RatchetProtocol.transcript(entry.getJSONObject("local"))==RatchetProtocol.transcript(bundle))
                val peerInfo=RatchetProtocol.parse(checkNotNull(database.settingsDao().getSettingValue("lanu_peer_id_${target.peer}")),4096)
                RatchetProtocol.verify(entry.getJSONObject("remote"),target.peer,own.id,peerInfo.getString("publicKey"))
                check(RatchetProtocol.epoch(bundle,entry.getJSONObject("remote"))==epoch)
            } finally {keys.close()}
        }
        val cipher=checkNotNull(database.settingsDao().getSettingValue(target.stateKey))
        val pending=JSONObject().put("target",target.encoded).put("bundle",bundle)
            .put("digest",RatchetTransactionStore.digest(cipher)).put("epoch",epoch ?: JSONObject.NULL)
        location(own,target.peer).write(signed(own,record(own,target.peer,stored,pending)))
        checkpoint(Checkpoint.PREPARED)
        database.withTransaction {
            check(database.settingsDao().getSettingValue(pointerKey(target.peer))==stored)
            applySelection(own,target.peer,pending)
        }
        checkpoint(Checkpoint.ROOM_COMMITTED)
        location(own,target.peer).write(signed(own,record(own,target.peer,target.encoded)))
        checkpoint(Checkpoint.FINALIZED)
    }
    private suspend fun applySelection(own:MessagingIdentity,peer:String,pending:JSONObject) {
        val target=parse(own,peer,pending.getString("target"));val bundle=pending.getJSONObject("bundle")
        RatchetProtocol.verify(bundle,own.id,peer,own.publicKey)
        val dao=database.settingsDao();val epoch=value(pending,"epoch")
        if(epoch!=null) {
            require(HEX.matches(epoch))
            dao.insertSetting(SettingsEntity("lanu_dr_pin_$peer",epoch))
            val conversation=UserCodes.conversationId(own.id,peer)
            dao.insertSetting(SettingsEntity("lanu_dr_epoch_$conversation",epoch))
            // Preserve old ciphertext/history. Quarantine only OLD, unsent
            // generation copies; they cannot starve new traffic or be rekeyed silently.
            database.openHelper.writableDatabase.execSQL("""
                UPDATE messages SET status='WAITING'
                WHERE conversationId=? AND senderPhone=? AND status IN ('PENDING','SENDING','WAITING')
                AND (content LIKE 'lanu2:%' OR (content LIKE 'lanu3-local:%' AND id NOT IN (
                  SELECT substr(`key`,length('lanu_dr_wire_epoch_')+1) FROM app_settings WHERE `key` GLOB 'lanu_dr_wire_epoch_*' AND value=?
                )))
            """.trimIndent(),arrayOf(conversation,own.id,epoch))
        } else check(dao.getSettingValue("lanu_dr_pin_$peer")==null)
        dao.insertSetting(SettingsEntity("lanu_dr_public_bundle_$peer",bundle.toString()))
        dao.insertSetting(SettingsEntity(pointerKey(peer),target.encoded))
    }
}
