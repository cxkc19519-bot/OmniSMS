package com.omnisms.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import java.util.concurrent.Executors

class SmsForegroundService:Service(){
    private val uploader=Executors.newSingleThreadExecutor()
    private val drainCoalescer=DrainCoalescer()
    private val liveSmsReceiver=SmsReceiver()
    private var liveSmsReceiverRegistered=false
    private val inboxObserver=object:ContentObserver(Handler(Looper.getMainLooper())){
        override fun onChange(selfChange:Boolean){if(!selfChange&&SecureStorage.isEnabled(this@SmsForegroundService))scheduleDrain()}
    }
    private var inboxObserverRegistered=false
    override fun onCreate(){super.onCreate();ensureLiveSmsReceiverRegistered();ensureInboxObserverRegistered();val manager=getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel(CHANNEL,"短信转发运行状态",NotificationManager.IMPORTANCE_LOW).apply{description="保持短信监听可靠运行；不会显示短信内容"})
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(com.omnisms.app.R.drawable.ic_launcher).setContentTitle("OmniSMS 正在运行").setContentText("新短信将安全转发，通知中不会显示短信内容").setContentIntent(open).setOngoing(true).build();startForeground(ID,notification)}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        ensureLiveSmsReceiverRegistered();ensureInboxObserverRegistered();scheduleDrain()
        return START_STICKY
    }
    private fun scheduleDrain(){
        if(!drainCoalescer.request())return
        runCatching{uploader.execute{
            try{
                do{drainOnce()}while(drainCoalescer.continueOrRelease())
            }catch(e:Exception){
                drainCoalescer.reset()
                Log.w("OmniSMS","upload_drain_failed_${e.javaClass.simpleName}")
            }
        }}.onFailure{
            drainCoalescer.reset()
            Log.w("OmniSMS","upload_handoff_failed_${it.javaClass.simpleName}")
        }
    }
    private fun drainOnce(){
        val wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OmniSMS:upload").apply{setReferenceCounted(false);acquire(30_000)}
        try{InboxReconciler.reconcile(applicationContext);if(UploadProcessor.drain(applicationContext)==UploadProcessor.Result.RETRY)UploadWorker.enqueue(applicationContext)}finally{if(wake.isHeld)wake.release()}
    }
    private fun ensureLiveSmsReceiverRegistered(){
        if(liveSmsReceiverRegistered)return
        runCatching{registerLiveSmsReceiver()}.onFailure{Log.w("OmniSMS","live_receiver_registration_failed_${it.javaClass.simpleName}")}
    }
    private fun ensureInboxObserverRegistered(){
        if(inboxObserverRegistered)return
        runCatching{
            contentResolver.registerContentObserver(Telephony.Sms.Inbox.CONTENT_URI,true,inboxObserver)
            inboxObserverRegistered=true
        }.onFailure{Log.w("OmniSMS","inbox_observer_registration_failed_${it.javaClass.simpleName}")}
    }
    private fun registerLiveSmsReceiver(){
        val filter=IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU){
            registerReceiver(liveSmsReceiver,filter,Manifest.permission.BROADCAST_SMS,null,Context.RECEIVER_EXPORTED)
        }else{
            registerReceiver(liveSmsReceiver,filter,Manifest.permission.BROADCAST_SMS,null)
        }
        liveSmsReceiverRegistered=true
    }
    override fun onDestroy(){
        if(liveSmsReceiverRegistered){unregisterReceiver(liveSmsReceiver);liveSmsReceiverRegistered=false}
        if(inboxObserverRegistered){contentResolver.unregisterContentObserver(inboxObserver);inboxObserverRegistered=false}
        uploader.shutdownNow();super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object{
        private const val CHANNEL="omnisms_status";private const val ID=1001;private const val ACTION_UPLOAD="com.omnisms.app.action.UPLOAD"
        fun ensureRunning(context:android.content.Context){
            try{context.startForegroundService(Intent(context,SmsForegroundService::class.java))}catch(_:IllegalStateException){UploadWorker.enqueue(context)}
        }
        fun requestUpload(context:android.content.Context){
            try{context.startForegroundService(Intent(context,SmsForegroundService::class.java).setAction(ACTION_UPLOAD))}catch(_:IllegalStateException){UploadWorker.enqueue(context)}
        }
    }
}
