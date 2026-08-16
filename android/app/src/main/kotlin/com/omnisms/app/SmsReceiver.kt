package com.omnisms.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.UserManager
import android.provider.Telephony
import java.util.concurrent.Executors

class SmsReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION||!SecureStorage.isEnabled(context))return
        val parts=Telephony.Sms.Intents.getMessagesFromIntent(intent);if(parts.isEmpty())return;val pending=goAsync()
        executor.execute{try{val body=parts.joinToString(""){it.messageBody.orEmpty()};val sender=parts.firstOrNull()?.originatingAddress.orEmpty().ifBlank{"未知发送方"};val receivedAt=parts.minOfOrNull{it.timestampMillis}?:System.currentTimeMillis();val slot=simSlot(intent)
            OutboxDatabase.get(context).insert(sender,body,receivedAt,slot,slot?.let{"SIM ${it+1}"}.orEmpty(),!isOnline(context));if(context.getSystemService(UserManager::class.java).isUserUnlocked)UploadWorker.enqueue(context)
        }finally{pending.finish()}}
    }
    @Suppress("DEPRECATION") private fun simSlot(intent:Intent):Int?{for(key in listOf("phone","slot","slot_id","simId")){val value=intent.extras?.get(key);if(value is Number&&value.toInt()>=0)return value.toInt()};return null}
    private fun isOnline(context:Context):Boolean{val cm=context.getSystemService(ConnectivityManager::class.java);val network=cm.activeNetwork?:return false;val caps=cm.getNetworkCapabilities(network)?:return false;return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}
    companion object{private val executor=Executors.newSingleThreadExecutor()}
}
