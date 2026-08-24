package com.omnisms.app

import android.app.Notification
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.SubscriptionManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MessageNotificationListenerService:NotificationListenerService(){
    private val executor=Executors.newSingleThreadScheduledExecutor()
    private val pending=mutableMapOf<String,Candidate>()
    private val scheduled=mutableMapOf<String,ScheduledFuture<*>>()
    private var suppressExistingBefore=0L

    override fun onListenerConnected(){
        super.onListenerConnected()
        if(!SecureStorage.notificationBaselined(this)){
            suppressExistingBefore=System.currentTimeMillis()
            SecureStorage.markNotificationBaselined(this)
            Log.i("OmniSMS","notification_baseline_created")
            return
        }
        activeNotifications?.filter{System.currentTimeMillis()-it.postTime<=MAX_AGE}?.forEach{handleAsync(it)}
    }

    override fun onNotificationPosted(sbn:StatusBarNotification){
        if(!NotificationPolicy.isAllowedPackage(sbn.packageName))return
        handleAsync(sbn)
    }

    override fun onDestroy(){executor.shutdownNow();super.onDestroy()}

    private fun handleAsync(sbn:StatusBarNotification){
        if(!NotificationPolicy.isAllowedPackage(sbn.packageName)||(sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY)!=0||!SecureStorage.isEnabled(this)||sbn.postTime<suppressExistingBefore)return
        executor.execute{runCatching{stage(sbn)}.onFailure{Log.w("OmniSMS","notification_deferred_${it.javaClass.simpleName}")}}
    }

    private fun stage(sbn:StatusBarNotification){
        val candidate=extract(sbn)?:run{Log.i("OmniSMS","notification_missing_content");return}
        val current=pending[sbn.key]
        pending[sbn.key]=if(current==null||candidate.body.length>=current.body.length)candidate else current
        scheduled.remove(sbn.key)?.cancel(false)
        scheduled[sbn.key]=executor.schedule({flush(sbn.key)},DEBOUNCE_MILLIS,TimeUnit.MILLISECONDS)
    }

    private fun flush(key:String){
        scheduled.remove(key);val candidate=pending.remove(key)?:return
        runCatching{process(candidate)}.onFailure{Log.w("OmniSMS","notification_deferred_${it.javaClass.simpleName}")}
    }

    private fun process(candidate:Candidate){
        val eventFingerprint=MessageFingerprint.create("notification:${candidate.notificationKey}","",candidate.sourceTimestamp)
        if(NotificationPolicy.isRecentStandardSms(System.currentTimeMillis(),SecureStorage.lastStandardSmsAt(this))){OutboxDatabase.get(this).rememberSourceFingerprint(eventFingerprint,candidate.receivedAt);Log.i("OmniSMS","notification_ignored_recent_sms");return}
        matchingStandardSms(candidate.body)?.let{standard->
            val db=OutboxDatabase.get(this);db.rememberSourceFingerprint(eventFingerprint,candidate.receivedAt)
            val inserted=db.insert(standard.sender,standard.body,standard.receivedAt,standard.simSlot,standard.simSlot?.let{"SIM ${it+1}"}.orEmpty(),!isOnline(this),standard.sourceFingerprint)
            Log.i("OmniSMS",if(inserted)"notification_standard_sms_queued" else "notification_ignored_standard_sms")
            SmsForegroundService.requestUpload(this);UploadWorker.enqueue(this);return
        }
        val inserted=OutboxDatabase.get(this).insert(candidate.sender,candidate.body,candidate.receivedAt,null,"5G消息",!isOnline(this),eventFingerprint)
        Log.i("OmniSMS",if(inserted)"notification_queue_inserted" else "notification_duplicate_ignored")
        SmsForegroundService.requestUpload(this);UploadWorker.enqueue(this)
    }

    @Suppress("DEPRECATION")
    private fun extract(sbn:StatusBarNotification):Candidate?{
        val notification=sbn.notification;val postTime=sbn.postTime
        val extras=notification.extras
        val messages=Notification.MessagingStyle.Message.getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        val latest=messages.maxByOrNull{it.timestamp}
        val body=NotificationPolicy.preferredBody(listOfNotNull(latest?.text?.toString(),extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()?.toString()))?:return null
        val sender=(latest?.senderPerson?.name?:latest?.sender?:extras.getCharSequence(Notification.EXTRA_TITLE)?:extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString()?.trim().orEmpty().ifBlank{"5G消息"}
        val sourceTimestamp=(latest?.timestamp?.takeIf{it>0}?:notification.`when`.takeIf{it>0}?:postTime)
        return Candidate(sbn.key,sender,body,sourceTimestamp,postTime)
    }

    private fun matchingStandardSms(body:String):StandardSms?{
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,arrayOf(Telephony.TextBasedSmsColumns.ADDRESS,Telephony.TextBasedSmsColumns.BODY,Telephony.TextBasedSmsColumns.DATE,Telephony.TextBasedSmsColumns.DATE_SENT,Telephony.TextBasedSmsColumns.SUBSCRIPTION_ID),null,null,"_id DESC LIMIT 30")?.use{rows->
            val addressIndex=rows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.ADDRESS);val bodyIndex=rows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.BODY);val dateIndex=rows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE);val dateSentIndex=rows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE_SENT);val subscriptionIndex=rows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.SUBSCRIPTION_ID);var checked=0
            while(rows.moveToNext()&&checked++<30){val storedBody=rows.getString(bodyIndex).orEmpty();if(storedBody!=body)continue;val sender=rows.getString(addressIndex).orEmpty().ifBlank{"未知发送方"};val receivedAt=rows.getLong(dateIndex);val sentAt=rows.getLong(dateSentIndex).takeIf{it>0}?:receivedAt;val slot=runCatching{SubscriptionManager.getSlotIndex(rows.getInt(subscriptionIndex))}.getOrNull()?.takeIf{it>=0};return StandardSms(sender,storedBody,receivedAt,slot,MessageFingerprint.create(sender,storedBody,sentAt))}
        }
        return null
    }

    private fun isOnline(context:Context):Boolean{
        val manager=context.getSystemService(ConnectivityManager::class.java);val network=manager.activeNetwork?:return false
        val capabilities=manager.getNetworkCapabilities(network)?:return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private data class Candidate(val notificationKey:String,val sender:String,val body:String,val sourceTimestamp:Long,val receivedAt:Long)
    private data class StandardSms(val sender:String,val body:String,val receivedAt:Long,val simSlot:Int?,val sourceFingerprint:String)
    companion object{private const val MAX_AGE=24*60*60*1000L;private const val DEBOUNCE_MILLIS=2_500L}
}
