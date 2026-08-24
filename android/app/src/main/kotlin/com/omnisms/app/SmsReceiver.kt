package com.omnisms.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Telephony
import android.util.Log

class SmsReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION||!SecureStorage.isEnabled(context))return
        val parts=Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if(parts.isEmpty()){
            // ColorOS can rebroadcast an SMS before its inbox provider is readable.
            // Retry reconciliation after the provider has committed the message.
            SmsForegroundService.requestSmsRecovery(context);UploadWorker.enqueue(context);return
        }
        SecureStorage.markStandardSmsReceived(context)
        // Persist the encrypted row before onReceive returns. Network I/O remains
        // asynchronous, but the SMS itself can no longer be lost with this process.
        try{val body=parts.joinToString(""){it.messageBody.orEmpty()};val sender=parts.firstOrNull()?.originatingAddress.orEmpty().ifBlank{"未知发送方"};val receivedAt=parts.minOfOrNull{it.timestampMillis}?:System.currentTimeMillis();val slot=simSlot(intent)
            val inserted=OutboxDatabase.get(context).insert(sender,body,receivedAt,slot,slot?.let{"SIM ${it+1}"}.orEmpty(),!isOnline(context),MessageFingerprint.create(sender,body,receivedAt));Log.i("OmniSMS",if(inserted)"queue_inserted" else "queue_duplicate_ignored");SmsForegroundService.requestUpload(context);UploadWorker.enqueue(context)
        }catch(e:Exception){Log.e("OmniSMS","queue_write_failed_${e.javaClass.simpleName}");SmsForegroundService.requestSmsRecovery(context);UploadWorker.enqueue(context)}
    }
    @Suppress("DEPRECATION") private fun simSlot(intent:Intent):Int?{for(key in listOf("phone","slot","slot_id","simId")){val value=intent.extras?.get(key);if(value is Number&&value.toInt()>=0)return value.toInt()};return null}
    private fun isOnline(context:Context):Boolean{val cm=context.getSystemService(ConnectivityManager::class.java);val network=cm.activeNetwork?:return false;val caps=cm.getNetworkCapabilities(network)?:return false;return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}

}
