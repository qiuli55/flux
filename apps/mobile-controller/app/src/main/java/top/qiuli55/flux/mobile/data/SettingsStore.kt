package top.qiuli55.flux.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.fluxSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "flux_settings",
)

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val TOKEN = stringPreferencesKey("token")
    }

    val config: Flow<ConnectionConfig> = appContext.fluxSettingsStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            ConnectionConfig(
                baseUrl = prefs[Keys.BASE_URL] ?: ConnectionConfig.DEFAULT_BASE_URL,
                token = decodeStoredToken(prefs[Keys.TOKEN]),
            )
        }

    suspend fun save(baseUrl: String, token: String) {
        appContext.fluxSettingsStore.edit { prefs ->
            prefs[Keys.BASE_URL] = baseUrl.trim()
            prefs[Keys.TOKEN] = encryptToken(token)
        }
    }

    private fun decodeStoredToken(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        if (!raw.startsWith(ENCRYPTED_PREFIX)) {
            // 兼容 v0.1.0/v0.1.1 已经保存到 DataStore 的旧明文 Token。
            // 下次保存时会自动迁移为加密格式。
            return raw
        }
        return runCatching { decryptToken(raw.removePrefix(ENCRYPTED_PREFIX)) }.getOrDefault("")
    }

    private fun encryptToken(token: String): String {
        if (token.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        val packed = ByteArray(1 + iv.size + ciphertext.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)
        return ENCRYPTED_PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decryptToken(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.isNotEmpty())
        val ivLength = packed[0].toInt() and 0xFF
        require(ivLength in 12..16 && packed.size > ivLength + 1)
        val iv = packed.copyOfRange(1, 1 + ivLength)
        val ciphertext = packed.copyOfRange(1 + ivLength, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, iv),
        )
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing != null) {
            return existing as SecretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        val generated = generator.generateKey()
        return generated
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "flux.mobile.token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENCRYPTED_PREFIX = "v1:"
    }
}
