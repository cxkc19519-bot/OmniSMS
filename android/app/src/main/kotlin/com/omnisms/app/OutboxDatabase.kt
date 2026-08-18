package com.omnisms.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

internal data class OutboxMessage(
    val id: String, val sender: String, val body: String, val receivedAt: Long, val simSlot: Int?, val simLabel: String,
    val queuedOffline: Boolean, val attemptCount: Int
)

internal class OutboxDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.createDeviceProtectedStorageContext(), "omnisms_outbox.sqlite3", null, 2
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE outbox_message(
            message_id TEXT PRIMARY KEY, sender_encrypted TEXT, body_encrypted TEXT, received_at INTEGER NOT NULL,
            sim_slot INTEGER, sim_label TEXT NOT NULL, queued_offline INTEGER NOT NULL, state TEXT NOT NULL,
            attempt_count INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL, last_error_code TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL, completed_at INTEGER, source_fingerprint TEXT
        )""")
        db.execSQL("CREATE INDEX idx_outbox_due ON outbox_message(state,next_attempt_at)")
        db.execSQL("CREATE INDEX idx_outbox_created ON outbox_message(created_at)")
        db.execSQL("CREATE UNIQUE INDEX idx_outbox_source_fingerprint ON outbox_message(source_fingerprint) WHERE source_fingerprint IS NOT NULL")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2){
            db.execSQL("ALTER TABLE outbox_message ADD COLUMN source_fingerprint TEXT")
            db.execSQL("CREATE UNIQUE INDEX idx_outbox_source_fingerprint ON outbox_message(source_fingerprint) WHERE source_fingerprint IS NOT NULL")
        }
    }

    fun insert(sender: String, body: String, receivedAt: Long, simSlot: Int?, simLabel: String, queuedOffline: Boolean, sourceFingerprint:String?=null): Boolean = synchronized(this) {
        val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        val values = ContentValues().apply { put("message_id",id);put("sender_encrypted",SecureStorage.encrypt(sender.toByteArray()));put("body_encrypted",SecureStorage.encrypt(body.toByteArray()));put("received_at",receivedAt);if(simSlot==null)putNull("sim_slot")else put("sim_slot",simSlot);put("sim_label",simLabel);put("queued_offline",if(queuedOffline)1 else 0);put("state","pending");put("next_attempt_at",now);put("created_at",now);if(sourceFingerprint==null)putNull("source_fingerprint")else put("source_fingerprint",sourceFingerprint) }
        return@synchronized writableDatabase.insertWithOnConflict("outbox_message",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    }

    fun rememberSourceFingerprint(sourceFingerprint:String,receivedAt:Long):Boolean=synchronized(this){
        val now=System.currentTimeMillis();val values=ContentValues().apply{put("message_id",UUID.randomUUID().toString());putNull("sender_encrypted");putNull("body_encrypted");put("received_at",receivedAt);putNull("sim_slot");put("sim_label","5G消息去重");put("queued_offline",0);put("state","sent");put("attempt_count",0);put("next_attempt_at",now);put("created_at",now);put("completed_at",now);put("source_fingerprint",sourceFingerprint)}
        return@synchronized writableDatabase.insertWithOnConflict("outbox_message",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    }

    fun claimNextDue(now: Long): OutboxMessage? = synchronized(this) {
        val db=writableDatabase; var message:OutboxMessage?=null; db.beginTransaction()
        try {
            db.query("outbox_message",arrayOf("message_id","sender_encrypted","body_encrypted","received_at","sim_slot","sim_label","queued_offline","attempt_count"),"state IN ('pending','retry') AND next_attempt_at<=?",arrayOf(now.toString()),null,null,"created_at","1").use { c ->
                if(c.moveToFirst()){
                    val slotIndex=c.getColumnIndexOrThrow("sim_slot");val attemptCount=c.getInt(7)+1
                    message=OutboxMessage(c.getString(0),SecureStorage.decrypt(c.getString(1)).toString(Charsets.UTF_8),SecureStorage.decrypt(c.getString(2)).toString(Charsets.UTF_8),c.getLong(3),if(c.isNull(slotIndex))null else c.getInt(slotIndex),c.getString(5),c.getInt(6)!=0,attemptCount)
                    db.execSQL("UPDATE outbox_message SET state='sending',attempt_count=? WHERE message_id=?",arrayOf<Any>(attemptCount,message!!.id))
                }
            };db.setTransactionSuccessful();return@synchronized message
        }finally{db.endTransaction()}
    }
    fun markSent(id:String,now:Long){val v=ContentValues().apply{put("state","sent");putNull("sender_encrypted");putNull("body_encrypted");put("completed_at",now);put("last_error_code","")};writableDatabase.update("outbox_message",v,"message_id=?",arrayOf(id))}
    fun markRetry(id:String,code:String,next:Long){val v=ContentValues().apply{put("state","retry");put("last_error_code",code);put("next_attempt_at",next)};writableDatabase.update("outbox_message",v,"message_id=?",arrayOf(id))}
    fun markPermanent(id:String,code:String){val v=ContentValues().apply{put("state","permanent_failed");put("last_error_code",code)};writableDatabase.update("outbox_message",v,"message_id=?",arrayOf(id))}
    fun recoverInterrupted(now:Long){writableDatabase.execSQL("UPDATE outbox_message SET state='retry',last_error_code='interrupted',next_attempt_at=? WHERE state='sending'",arrayOf(now))}
    fun cleanup(now:Long){writableDatabase.delete("outbox_message","created_at<? AND state IN ('sent','permanent_failed')",arrayOf((now-24*60*60*1000L).toString()))}
    fun counts():Pair<Int,Int>{readableDatabase.rawQuery("SELECT SUM(CASE WHEN state IN ('pending','retry','sending') THEN 1 ELSE 0 END),SUM(CASE WHEN state='permanent_failed' THEN 1 ELSE 0 END) FROM outbox_message",null).use{c->c.moveToFirst();return Pair(if(c.isNull(0))0 else c.getInt(0),if(c.isNull(1))0 else c.getInt(1))}}

    companion object { @Volatile private var instance:OutboxDatabase?=null; fun get(context:Context)=instance?:synchronized(this){instance?:OutboxDatabase(context).also{instance=it}} }
}
