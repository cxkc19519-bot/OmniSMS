package com.omnisms.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal class UploadWorker(context:Context,params:WorkerParameters):Worker(context,params){
    override fun doWork():Result{
        if(!SecureStorage.isEnabled(applicationContext))return Result.success()
        val config=SecureStorage.loadConfig(applicationContext)?:return Result.failure()
        val db=OutboxDatabase.get(applicationContext);val now=System.currentTimeMillis();db.recoverInterrupted(now);db.cleanup(now)
        repeat(20){val message=db.nextDue(System.currentTimeMillis())?:return Result.success();db.markAttempt(message.id)
            when(val result=MessageUploader.upload(config,message)){
                UploadResult.Success->db.markSent(message.id,System.currentTimeMillis())
                is UploadResult.Permanent->{db.markPermanent(message.id,result.code);if(result.code=="authentication_failed")return Result.failure()}
                is UploadResult.Retry->{val attempt=message.attemptCount+1;val delay=30_000L*(1L shl min(attempt-1,7));db.markRetry(message.id,result.code,System.currentTimeMillis()+delay);return Result.retry()}
            }}
        return Result.success()
    }
    companion object{
        private const val UNIQUE="omnisms-upload"
        fun enqueue(context:Context){
            val request=OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE,ExistingWorkPolicy.KEEP,request)
        }
    }
}
