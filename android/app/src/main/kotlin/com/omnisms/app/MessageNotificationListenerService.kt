package com.omnisms.app

import android.app.Notification
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
        if(NotificationPolicy.isRecentStandardSms(System.currentTimeMillis(),SecureStorage.lastStandardSmsAt(this))){Log.i("OmniSMS","notification_ignored_recent_sms");return}
        if(isStandardSmsAlreadyPresent(candidate.body)){Log.i("OmniSMS","notification_ignored_standard_sms");return}
        val eventFingerprint=MessageFingerprint.create("notification:${candidate.notificationKey}","",candidate.sourceTimestamp)
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

    private fun isStandardSmsAlreadyPresent(body:String):Boolean{
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,arrayOf(Telephony.TextBasedSmsColumns.BODY),null,null,"_id DESC LIMIT 30")?.use{rows->
            val bodyIndex=rows.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.BODY);var checked=0
            while(rows.moveToNext()&&checked++<30)if(rows.getString(bodyIndex).orEmpty()==body)return true
        }
        return false
    }

    private fun isOnline(context:Context):Boolean{
        val manager=context.getSystemService(ConnectivityManager::class.java);val network=manager.activeNetwork?:return false
        val capabilities=manager.getNetworkCapabilities(network)?:return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private data class Candidate(val notificationKey:String,val sender:String,val body:String,val sourceTimestamp:Long,val receivedAt:Long)
    companion object{private const val MAX_AGE=24*60*60*1000L;private const val DEBOUNCE_MILLIS=2_500L}
}
