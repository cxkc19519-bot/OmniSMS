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

internal class UploadWorker(context:Context,params:WorkerParameters):Worker(context,params){
    override fun doWork():Result{
        return when(UploadProcessor.drain(applicationContext)){
            UploadProcessor.Result.COMPLETE->Result.success()
            UploadProcessor.Result.RETRY->Result.retry()
            UploadProcessor.Result.AUTHENTICATION_FAILED->Result.failure()
        }
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
