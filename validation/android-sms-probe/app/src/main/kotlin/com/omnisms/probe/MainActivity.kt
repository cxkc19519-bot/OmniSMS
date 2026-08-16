package com.omnisms.probe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeForegroundService.start(this)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    @Deprecated("Android permission callback API")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_REQUEST) renderStatus()
    }

    private fun buildContent(): ScrollView {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        container.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.privacy_summary)
            textSize = 16f
            setPadding(0, padding / 2, 0, padding)
        })

        statusView = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        container.addView(statusView)

        container.addView(actionButton("授予短信权限") {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), SMS_PERMISSION_REQUEST)
        })
        container.addView(actionButton("刷新验证结果") { renderStatus() })
        container.addView(actionButton("打开本 App 系统设置") {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        })
        container.addView(actionButton("打开电池优化设置") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        return ScrollView(this).apply { addView(container) }
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
        }
    }

    private fun renderStatus() {
        if (!::statusView.isInitialized) return
        val preferences = ProbeStore.preferences(this)
        val permissionGranted = checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

        statusView.text = buildString {
            appendLine("短信权限：${if (permissionGranted) "已授权" else "未授权"}")
            appendLine("累计捕获：${preferences.getInt(SmsProbeReceiver.KEY_SMS_COUNT, 0)} 条")
            appendLine("最后捕获：${ProbeStore.formatTime(preferences.getLong(SmsProbeReceiver.KEY_LAST_CAPTURED_AT, 0L))}")
            appendLine("短信时间：${ProbeStore.formatTime(preferences.getLong(SmsProbeReceiver.KEY_LAST_SMS_TIMESTAMP, 0L))}")
            appendLine("发送方：${preferences.getString(SmsProbeReceiver.KEY_LAST_SENDER, "尚无记录")}")
            appendLine("短信分段：${preferences.getInt(SmsProbeReceiver.KEY_LAST_PART_COUNT, 0)}")
            appendLine("正文长度：${preferences.getInt(SmsProbeReceiver.KEY_LAST_BODY_LENGTH, 0)} 个字符")
            appendLine("正文摘要：${preferences.getString(SmsProbeReceiver.KEY_LAST_BODY_DIGEST, "尚无记录")}")
            appendLine("SIM 元数据：${preferences.getString(SmsProbeReceiver.KEY_LAST_SIM_METADATA, "尚无记录")}")
            appendLine("监听服务启动：${preferences.getInt(ProbeForegroundService.KEY_SERVICE_START_COUNT, 0)} 次")
            appendLine("最后服务启动：${ProbeStore.formatTime(preferences.getLong(ProbeForegroundService.KEY_LAST_SERVICE_START_AT, 0L))}")
            appendLine("启动广播：${preferences.getInt(BootProbeReceiver.KEY_BOOT_COUNT, 0)} 次")
            appendLine("最后启动广播：${ProbeStore.formatTime(preferences.getLong(BootProbeReceiver.KEY_LAST_BOOT_AT, 0L))}")
            appendLine("最后启动类型：${preferences.getString(BootProbeReceiver.KEY_LAST_BOOT_ACTION, "尚无记录")}")
            append("启动服务错误：${preferences.getString(BootProbeReceiver.KEY_LAST_BOOT_ERROR, "无")}")
        }
    }

    companion object {
        private const val SMS_PERMISSION_REQUEST = 1001
    }
}
