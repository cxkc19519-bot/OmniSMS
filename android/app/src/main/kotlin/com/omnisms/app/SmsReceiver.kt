package com.omnisms.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import java.util.concurrent.Executors

class SmsReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION||!SecureStorage.isEnabled(context))return
        val parts=Telephony.Sms.Intents.getMessagesFromIntent(intent);if(parts.isEmpty())return
        SecureStorage.markStandardSmsReceived(context)
        // goAsync() keeps this receiver's process important, but it does not keep a
        // sleeping device's CPU awake. Hold a short wake lock until the SMS is
        // durably queued and upload work has been handed off.
        val wake=context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OmniSMS:sms-received")
            .apply{setReferenceCounted(false);acquire(RECEIVER_WAKE_TIMEOUT_MS)}
        val pending=goAsync()
        executor.execute{try{val body=parts.joinToString(""){it.messageBody.orEmpty()};val sender=parts.firstOrNull()?.originatingAddress.orEmpty().ifBlank{"未知发送方"};val receivedAt=parts.minOfOrNull{it.timestampMillis}?:System.currentTimeMillis();val slot=simSlot(intent)
            val inserted=OutboxDatabase.get(context).insert(sender,body,receivedAt,slot,slot?.let{"SIM ${it+1}"}.orEmpty(),!isOnline(context),MessageFingerprint.create(sender,body,receivedAt));Log.i("OmniSMS",if(inserted)"queue_inserted" else "queue_duplicate_ignored");SmsForegroundService.requestUpload(context);UploadWorker.enqueue(context)
        }catch(e:Exception){Log.e("OmniSMS","queue_write_failed_${e.javaClass.simpleName}")
        }finally{if(wake.isHeld)wake.release();pending.finish()}}
    }
    @Suppress("DEPRECATION") private fun simSlot(intent:Intent):Int?{for(key in listOf("phone","slot","slot_id","simId")){val value=intent.extras?.get(key);if(value is Number&&value.toInt()>=0)return value.toInt()};return null}
    private fun isOnline(context:Context):Boolean{val cm=context.getSystemService(ConnectivityManager::class.java);val network=cm.activeNetwork?:return false;val caps=cm.getNetworkCapabilities(network)?:return false;return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}
    companion object{
        private const val RECEIVER_WAKE_TIMEOUT_MS=15_000L
        private val executor=Executors.newSingleThreadExecutor()
    }
}
