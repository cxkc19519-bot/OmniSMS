package com.omnisms.app

internal object NotificationPolicy {
    const val SYSTEM_MESSAGES_PACKAGE="com.android.mms"
    private const val STANDARD_SMS_SUPPRESSION_MILLIS=15_000L
    private val AGGREGATE_PREFIX=Regex("""^\[\s*\d+\s*条\s*]\s*""")
    fun isAllowedPackage(packageName:String?)=packageName==SYSTEM_MESSAGES_PACKAGE
    fun preferredBody(candidates:List<String>)=candidates.map{cleanAggregatePrefix(it)}.filter{it.isNotBlank()}.maxByOrNull{it.length}
    fun cleanAggregatePrefix(body:String)=body.trim().replaceFirst(AGGREGATE_PREFIX,"").trim()
    fun isRecentStandardSms(now:Long,lastStandardSmsAt:Long):Boolean{
        val age=now-lastStandardSmsAt
        return lastStandardSmsAt>0L&&age in 0..STANDARD_SMS_SUPPRESSION_MILLIS
    }
}
