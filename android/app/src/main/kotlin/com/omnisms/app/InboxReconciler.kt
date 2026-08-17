package com.omnisms.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.BaseColumns
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log

internal object InboxReconciler {
    private const val MAX_LOOKBACK=24*60*60*1000L

    fun ensureBaseline(context:Context){
        if(context.checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED||SecureStorage.recoveryId(context)!=null)return
        runCatching{SecureStorage.setRecoveryId(context,latestInboxId(context))}.onFailure{Log.w("OmniSMS","recovery_baseline_deferred_${it.javaClass.simpleName}")}
    }

    fun reconcile(context:Context):Int{
        if(!SecureStorage.isEnabled(context)||context.checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return 0
        val scanUntil=System.currentTimeMillis()
        val recoveryId=SecureStorage.recoveryId(context)?:run{ensureBaseline(context);return 0}
        val oldestAllowed=scanUntil-MAX_LOOKBACK
        var recovered=0
        var highestSeen=recoveryId
        try{
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(BaseColumns._ID,Telephony.TextBasedSmsColumns.ADDRESS,Telephony.TextBasedSmsColumns.BODY,Telephony.TextBasedSmsColumns.DATE,Telephony.TextBasedSmsColumns.DATE_SENT,Telephony.TextBasedSmsColumns.SUBSCRIPTION_ID),
                "${BaseColumns._ID}>? AND ${Telephony.TextBasedSmsColumns.DATE}>=?",
                arrayOf(recoveryId.toString(),oldestAllowed.toString()),
                "${BaseColumns._ID} ASC"
            )?.use{cursorRows->
                val idIndex=cursorRows.getColumnIndexOrThrow(BaseColumns._ID)
                val addressIndex=cursorRows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.ADDRESS)
                val bodyIndex=cursorRows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.BODY)
                val dateIndex=cursorRows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE)
                val dateSentIndex=cursorRows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE_SENT)
                val subscriptionIndex=cursorRows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.SUBSCRIPTION_ID)
                while(cursorRows.moveToNext()){
                    highestSeen=maxOf(highestSeen,cursorRows.getLong(idIndex))
                    val sender=cursorRows.getString(addressIndex).orEmpty().ifBlank{"未知发送方"}
                    val body=cursorRows.getString(bodyIndex).orEmpty()
                    val receivedAt=cursorRows.getLong(dateIndex)
                    val sentAt=cursorRows.getLong(dateSentIndex).takeIf{it>0}?:receivedAt
                    val subscriptionId=cursorRows.getInt(subscriptionIndex)
                    val slot=runCatching{SubscriptionManager.getSlotIndex(subscriptionId)}.getOrNull()?.takeIf{it>=0}
                    if(OutboxDatabase.get(context).insert(sender,body,receivedAt,slot,slot?.let{"SIM ${it+1}"}.orEmpty(),!isOnline(context),MessageFingerprint.create(sender,body,sentAt)))recovered++
                }
            }
            SecureStorage.setRecoveryId(context,highestSeen)
            if(recovered>0)Log.i("OmniSMS","queue_recovered_$recovered")
        }catch(e:Exception){Log.w("OmniSMS","recovery_deferred_${e.javaClass.simpleName}")}
        return recovered
    }

    private fun latestInboxId(context:Context):Long{
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,arrayOf(BaseColumns._ID),null,null,"${BaseColumns._ID} DESC")?.use{rows->if(rows.moveToFirst())return rows.getLong(0)}
        return 0L
    }

    private fun isOnline(context:Context):Boolean{
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val network=manager.activeNetwork?:return false
        val capabilities=manager.getNetworkCapabilities(network)?:return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
