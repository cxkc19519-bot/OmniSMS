package com.omnisms.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager

class BootReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(!SecureStorage.isEnabled(context))return;context.startForegroundService(Intent(context,SmsForegroundService::class.java));if(context.getSystemService(UserManager::class.java).isUserUnlocked)UploadWorker.enqueue(context)}}
