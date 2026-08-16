package com.omnisms.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

class ProbeForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val preferences = ProbeStore.preferences(this)
        preferences.edit()
            .putInt(
                KEY_SERVICE_START_COUNT,
                preferences.getInt(KEY_SERVICE_START_COUNT, 0) + 1
            )
            .putLong(KEY_LAST_SERVICE_START_AT, System.currentTimeMillis())
            .apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "短信监听测试",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 OmniSMS 测试接收器在 ColorOS 后台运行"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("OmniSMS 短信监听测试运行中")
            .setContentText("仅进行本地接收验证，不会上传短信")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val KEY_SERVICE_START_COUNT = "service_start_count"
        const val KEY_LAST_SERVICE_START_AT = "last_service_start_at"

        private const val CHANNEL_ID = "sms_probe_service"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProbeForegroundService::class.java))
        }
    }
}
