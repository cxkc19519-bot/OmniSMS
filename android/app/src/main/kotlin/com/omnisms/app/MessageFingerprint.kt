package com.omnisms.app

import java.security.MessageDigest

internal object MessageFingerprint {
    fun create(sender:String,body:String,sourceTimestamp:Long):String{
        val canonical="v1\u0000$sourceTimestamp\u0000$sender\u0000$body".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(canonical).joinToString(""){"%02x".format(it)}
    }
}
