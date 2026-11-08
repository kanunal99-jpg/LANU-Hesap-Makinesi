package com.example.core.messaging

import android.content.Context
import com.example.core.database.AppDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns one device-state journal. Startup/background sync never creates keys. */
class RatchetCoordinator(private val context:Context,private val database:AppDatabase) {
    private val mutex=Mutex()
    private var owner:String?=null
    private var active:RatchetSessionService?=null
    private var persistence:RatchetTransactionStore?=null
    internal suspend fun session(identity:MessagingIdentity,explicitCreate:Boolean=false):RatchetSessionService=mutex.withLock {
        active?.let { check(owner==identity.id);return@withLock it }
        val stored=database.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY)
        if(stored==null) check(explicitCreate && !database.settingsDao().hasRatchetSettings()) {
            "Bu cihazda güvenli oturum anahtarı yok. Mevcut pin ve arşiv korundu; açık oturum yenilemesi gerekiyor."
        }
        val keys=RatchetSecrets.open(context,identity.id,create=stored==null)
        try {
            val store=RatchetTransactionStore(database,RatchetFileJournal(context.noBackupFilesDir,identity.id),keys)
            store.recover()
            RatchetSessionService(database,store,keys).also {owner=identity.id;persistence=store;active=it}
        } catch(error:Throwable) {keys.close();throw error}
    }
    internal suspend fun recoverExisting(identity:MessagingIdentity) {
        if(database.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY)!=null) {
            session(identity)
            persistence!!.recover()
        }
    }
}
