package com.omnisms.app

internal object NotificationPolicy {
    const val SYSTEM_MESSAGES_PACKAGE="com.android.mms"
    private const val STANDARD_SMS_SUPPRESSION_MILLIS=15_000L
    fun isAllowedPackage(packageName:String?)=packageName==SYSTEM_MESSAGES_PACKAGE
    fun preferredBody(candidates:List<String>)=candidates.map{it.trim()}.filter{it.isNotBlank()}.maxByOrNull{it.length}
    fun isRecentStandardSms(now:Long,lastStandardSmsAt:Long):Boolean{
        val age=now-lastStandardSmsAt
        return lastStandardSmsAt>0L&&age in 0..STANDARD_SMS_SUPPRESSION_MILLIS
    }
}
