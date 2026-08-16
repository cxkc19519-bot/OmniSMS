package com.omnisms.probe

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object ProbeStore {
    private const val PREFERENCES_NAME = "probe_results"
    private const val KEY_MIGRATED = "device_storage_migrated"

    fun preferences(context: Context): SharedPreferences {
        val deviceContext = context.createDeviceProtectedStorageContext()
        val devicePreferences =
            deviceContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val userManager = context.getSystemService(UserManager::class.java)

        if (userManager.isUserUnlocked && !devicePreferences.getBoolean(KEY_MIGRATED, false)) {
            deviceContext.moveSharedPreferencesFrom(context, PREFERENCES_NAME)
            devicePreferences.edit().putBoolean(KEY_MIGRATED, true).commit()
        }
        return devicePreferences
    }

    fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return "尚无记录"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.SIMPLIFIED_CHINESE).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date(epochMillis))
    }
}
