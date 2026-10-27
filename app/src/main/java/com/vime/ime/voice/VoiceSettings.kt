package com.vime.ime.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vime.ime.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class VoiceConfig(val endpoint: String, val model: String, val key: String)
object VoiceSettings {
    const val SYSTEM = "system"
    const val CLOUD = "cloud"
    fun mode(c: Context) = prefs(c).getString("mode",SYSTEM).takeIf { it==CLOUD } ?: SYSTEM
    fun setMode(c: Context, value: String) { require(value in listOf(SYSTEM,CLOUD));prefs(c).edit().putString("mode",value).apply() }
    fun provider(c: Context) = prefs(c).getString("system_provider", "").orEmpty()
    fun setProvider(c: Context,value: String) { prefs(c).edit().putString("system_provider",value).apply() }
    fun languageChoice(c: Context) = prefs(c).getString("language","keyboard").takeIf { it in listOf("keyboard","zh-CN","en-US") } ?: "keyboard"
    fun language(c: Context, keyboard: String) = languageChoice(c).let { if(it=="keyboard") keyboard else it }
    fun setLanguage(c: Context,value: String) { require(value in listOf("keyboard","zh-CN","en-US"));prefs(c).edit().putString("language",value).apply() }
    private const val ALIAS = "vime_voice_key_v1"
    private fun prefs(c: Context) = c.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(c: Context): VoiceConfig {
        val p = prefs(c)
        val key = runCatching {
            val stored = p.getString("encrypted_key", null) ?: return@runCatching ""
            val iv = Base64.decode(p.getString("iv", ""), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
        return VoiceConfig(p.getString("endpoint", VoiceProtocol.ENDPOINT)!!, p.getString("model", VoiceProtocol.MODEL)!!, key)
    }
    fun save(c: Context, config: VoiceConfig) {
        VoiceProtocol.validate(config.endpoint, config.model, config.key, BuildConfig.DEBUG)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret())
        val encrypted = cipher.doFinal(config.key.toByteArray(Charsets.UTF_8))
        check(prefs(c).edit().putString("endpoint", config.endpoint).putString("model", config.model)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("encrypted_key", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "语音设置保存失败" }
    }
    fun clear(c: Context) { prefs(c).edit().remove("iv").remove("encrypted_key").apply() }
}
