package com.omnisms.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.security.MessageDigest

class SmsProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) return

        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
        val sender = parts.firstOrNull()?.originatingAddress.orEmpty()
        val smsTimestamp = parts.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
        val simMetadata = readSimMetadata(intent)

        val preferences = ProbeStore.preferences(context)
        val newCount = preferences.getInt(KEY_SMS_COUNT, 0) + 1
        preferences.edit()
            .putInt(KEY_SMS_COUNT, newCount)
            .putLong(KEY_LAST_CAPTURED_AT, System.currentTimeMillis())
            .putLong(KEY_LAST_SMS_TIMESTAMP, smsTimestamp)
            .putInt(KEY_LAST_PART_COUNT, parts.size)
            .putInt(KEY_LAST_BODY_LENGTH, body.length)
            .putString(KEY_LAST_BODY_DIGEST, sha256Prefix(body))
            .putString(KEY_LAST_SENDER, maskSender(sender))
            .putString(KEY_LAST_SIM_METADATA, simMetadata)
            .apply()
    }

    @Suppress("DEPRECATION")
    private fun readSimMetadata(intent: Intent): String {
        val keys = listOf(
            "subscription",
            "subscription_id",
            "sub_id",
            "slot",
            "slot_id",
            "simId",
            "phone"
        )
        val values = keys.mapNotNull { key ->
            if (!intent.hasExtra(key)) return@mapNotNull null
            val value = intent.extras?.get(key)
            if (value is Number || value is String) "$key=$value" else null
        }
        return values.joinToString().ifBlank { "系统未提供可识别的 SIM 标识" }
    }

    private fun maskSender(sender: String): String {
        if (sender.isBlank()) return "未知发送方"
        if (sender.length <= 4) return "****"
        return "*".repeat(sender.length - 4) + sender.takeLast(4)
    }

    private fun sha256Prefix(body: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        const val KEY_SMS_COUNT = "sms_count"
        const val KEY_LAST_CAPTURED_AT = "last_captured_at"
        const val KEY_LAST_SMS_TIMESTAMP = "last_sms_timestamp"
        const val KEY_LAST_PART_COUNT = "last_part_count"
        const val KEY_LAST_BODY_LENGTH = "last_body_length"
        const val KEY_LAST_BODY_DIGEST = "last_body_digest"
        const val KEY_LAST_SENDER = "last_sender"
        const val KEY_LAST_SIM_METADATA = "last_sim_metadata"
    }
}
