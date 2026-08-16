package com.omnisms.app

import android.content.Context
import android.util.Log
import kotlin.math.min

internal object UploadProcessor {
    enum class Result { COMPLETE, RETRY, AUTHENTICATION_FAILED }
    private const val TAG="OmniSMS"
    private val lock=Any()

    fun drain(context:Context):Result=synchronized(lock){
        if(!SecureStorage.isEnabled(context))return@synchronized Result.COMPLETE
        val config=SecureStorage.loadConfig(context)?:return@synchronized Result.AUTHENTICATION_FAILED
        val db=OutboxDatabase.get(context);val now=System.currentTimeMillis();db.recoverInterrupted(now);db.cleanup(now)
        repeat(20){
            val message=db.claimNextDue(System.currentTimeMillis())?:return@synchronized Result.COMPLETE
            when(val result=MessageUploader.upload(config,message)){
                UploadResult.Success->{db.markSent(message.id,System.currentTimeMillis());Log.i(TAG,"upload_sent")}
                is UploadResult.Permanent->{db.markPermanent(message.id,result.code);Log.w(TAG,"upload_permanent_${result.code}");if(result.code=="authentication_failed")return@synchronized Result.AUTHENTICATION_FAILED}
                is UploadResult.Retry->{
                    val delay=30_000L*(1L shl min(message.attemptCount-1,7));db.markRetry(message.id,result.code,System.currentTimeMillis()+delay);Log.w(TAG,"upload_retry_${result.code}");return@synchronized Result.RETRY
                }
            }
        }
        Result.COMPLETE
    }
}
