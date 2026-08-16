package com.omnisms.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val preferences = ProbeStore.preferences(context)
        preferences.edit()
            .putInt(KEY_BOOT_COUNT, preferences.getInt(KEY_BOOT_COUNT, 0) + 1)
            .putLong(KEY_LAST_BOOT_AT, System.currentTimeMillis())
            .putString(KEY_LAST_BOOT_ACTION, intent.action)
            .commit()

        runCatching { ProbeForegroundService.start(context) }
            .onFailure { error ->
                preferences.edit()
                    .putString(KEY_LAST_BOOT_ERROR, error.javaClass.simpleName)
                    .commit()
            }
    }

    companion object {
        const val KEY_BOOT_COUNT = "boot_count"
        const val KEY_LAST_BOOT_AT = "last_boot_at"
        const val KEY_LAST_BOOT_ACTION = "last_boot_action"
        const val KEY_LAST_BOOT_ERROR = "last_boot_error"
    }
}
