package com.omnisms.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.Executors

class SmsForegroundService:Service(){
    private val uploader=Executors.newSingleThreadExecutor()
    override fun onCreate(){super.onCreate();val manager=getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel(CHANNEL,"短信转发运行状态",NotificationManager.IMPORTANCE_LOW).apply{description="保持短信监听可靠运行；不会显示短信内容"})
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(com.omnisms.app.R.drawable.ic_launcher).setContentTitle("OmniSMS 正在运行").setContentText("新短信将安全转发，通知中不会显示短信内容").setContentIntent(open).setOngoing(true).build();startForeground(ID,notification)}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_UPLOAD)uploader.execute{
            val wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OmniSMS:upload").apply{setReferenceCounted(false);acquire(30_000)}
            try{if(UploadProcessor.drain(applicationContext)==UploadProcessor.Result.RETRY)UploadWorker.enqueue(applicationContext)}finally{if(wake.isHeld)wake.release()}
        }
        return START_STICKY
    }
    override fun onDestroy(){uploader.shutdownNow();super.onDestroy()}
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
