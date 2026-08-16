package com.omnisms.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal sealed interface UploadResult { data object Success:UploadResult; data class Retry(val code:String):UploadResult; data class Permanent(val code:String):UploadResult }

internal object MessageUploader {
    fun upload(config:ServerConfig,message:OutboxMessage):UploadResult {
        val path="/v1/messages";val timestamp=Instant.now().toString();val nonce=UUID.randomUUID().toString()
        val body=JSONObject().put("messageId",message.id).put("sender",message.sender).put("body",message.body)
            .put("receivedAt",Instant.ofEpochMilli(message.receivedAt).toString()).put("simSlot",message.simSlot?:JSONObject.NULL)
            .put("simLabel",message.simLabel).put("queuedOffline",message.queuedOffline).put("appVersion",BuildConfig.VERSION_NAME)
            .toString().toByteArray(Charsets.UTF_8)
        val signature=RequestSigner.sign("POST",path,timestamp,nonce,message.id,body,config.deviceSecret)
        val connection=(URL(config.endpoint+path).openConnection() as HttpsURLConnection).apply{
            requestMethod="POST";connectTimeout=10_000;readTimeout=15_000;doOutput=true;instanceFollowRedirects=false
            setRequestProperty("Content-Type","application/json");setRequestProperty("X-Device-Id",config.deviceId);setRequestProperty("X-Timestamp",timestamp)
            setRequestProperty("X-Nonce",nonce);setRequestProperty("Idempotency-Key",message.id);setRequestProperty("X-Signature",signature)
        }
        return try { connection.outputStream.use{it.write(body)}; when(connection.responseCode){HttpURLConnection.HTTP_OK,HttpURLConnection.HTTP_ACCEPTED->UploadResult.Success;HttpURLConnection.HTTP_BAD_REQUEST->UploadResult.Permanent("invalid_message");HttpURLConnection.HTTP_UNAUTHORIZED,HttpURLConnection.HTTP_FORBIDDEN->UploadResult.Permanent("authentication_failed");HttpURLConnection.HTTP_CLIENT_TIMEOUT,429->UploadResult.Retry("server_busy");in 500..599->UploadResult.Retry("server_error");else->UploadResult.Permanent("unexpected_response")}} catch(_:Exception){UploadResult.Retry("network_error")} finally {connection.disconnect()}
    }
}
