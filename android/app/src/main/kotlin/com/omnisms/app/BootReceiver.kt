package com.omnisms.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager

class BootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action !in ALLOWED_ACTIONS||!SecureStorage.isEnabled(context))return
        SmsForegroundService.ensureRunning(context);SmsForegroundService.requestUpload(context)
    }
    companion object{private val ALLOWED_ACTIONS=setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED,Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_USER_UNLOCKED,Intent.ACTION_MY_PACKAGE_REPLACED)}
}
