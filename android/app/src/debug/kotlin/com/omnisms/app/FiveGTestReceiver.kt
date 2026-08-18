package com.omnisms.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant

class FiveGTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || !SecureStorage.isEnabled(context)) return
        val receivedAt = Instant.now().toEpochMilli()
        val body = NotificationPolicy.cleanAggregatePrefix("[2 条] 这是一条固定的虚构5G通知测试，不包含真实短信或验证码。")
        OutboxDatabase.get(context).insert("OmniSMS 5G测试", body, receivedAt, null, "5G消息", false)
        SmsForegroundService.requestUpload(context)
        UploadWorker.enqueue(context)
    }

    companion object {
        private const val ACTION = "com.omnisms.app.DEBUG_SEND_5G_TEST"
    }
}
