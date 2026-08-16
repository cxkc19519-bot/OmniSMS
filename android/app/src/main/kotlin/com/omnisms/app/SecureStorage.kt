package com.omnisms.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class ServerConfig(val endpoint: String, val deviceId: String, val deviceSecret: ByteArray)

internal object SecureStorage {
    private const val KEY_ALIAS = "omnisms_data_key_v1"
    private const val PREFS = "omnisms_secure_config"
    private const val CONFIG = "server_config"
    private const val ENABLED = "forwarding_enabled"

    fun saveConfig(context: Context, endpoint: String, deviceId: String, secretBase64Url: String) {
        val normalizedEndpoint = endpoint.trim().removeSuffix("/")
        require(normalizedEndpoint.startsWith("https://")) { "服务器地址必须以 https:// 开头" }
        require(deviceId.trim().length in 8..128) { "设备编号格式不正确" }
        val secret = Base64.decode(secretBase64Url.trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(secret.size >= 32) { "设备密钥格式不正确" }
        val json = JSONObject().put("endpoint", normalizedEndpoint).put("deviceId", deviceId.trim())
            .put("deviceSecret", Base64.encodeToString(secret, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        preferences(context).edit().putString(CONFIG, encrypt(json.toString().toByteArray(Charsets.UTF_8))).apply()
    }

    fun loadConfig(context: Context): ServerConfig? = runCatching {
        val encrypted = preferences(context).getString(CONFIG, null) ?: return null
        val json = JSONObject(decrypt(encrypted).toString(Charsets.UTF_8))
        ServerConfig(json.getString("endpoint"), json.getString("deviceId"), Base64.decode(json.getString("deviceSecret"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
    }.getOrNull()

    fun setEnabled(context: Context, enabled: Boolean) { preferences(context).edit().putBoolean(ENABLED, enabled).apply() }
    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, false)

    private fun preferences(context: Context) = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = ByteArray(1 + cipher.iv.size + cipher.getOutputSize(plain.size)); packed[0] = cipher.iv.size.toByte()
        System.arraycopy(cipher.iv, 0, packed, 1, cipher.iv.size); val encrypted = cipher.doFinal(plain)
        System.arraycopy(encrypted, 0, packed, 1 + cipher.iv.size, encrypted.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(value: String): ByteArray {
        val packed = Base64.decode(value, Base64.NO_WRAP); require(packed.isNotEmpty())
        val ivLength = packed[0].toInt() and 0xff; require(ivLength in 12..32 && packed.size > 1 + ivLength)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(1, 1 + ivLength)))
        return cipher.doFinal(packed.copyOfRange(1 + ivLength, packed.size))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUnlockedDeviceRequired(false).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
}
