package com.omnisms.app

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object RequestSigner {
    fun sign(method: String, path: String, timestamp: String, nonce: String, idempotencyKey: String, body: ByteArray, secret: ByteArray): String {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val canonical = listOf(method.uppercase(), path, timestamp, nonce, idempotencyKey, bodyHash).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
    }
}
