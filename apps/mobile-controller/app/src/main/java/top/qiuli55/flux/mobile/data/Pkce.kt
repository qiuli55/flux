package top.qiuli55.flux.mobile.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth PKCE（RFC 7636，S256）。
 *
 * `flux://auth` 这类自定义 scheme 可能被同 scheme 的其他 App 抢注；把 verifier
 * 留在本机、只在兑换时出示，截获登录码也无法换到会话。
 */
object Pkce {
    /** 32 字节随机数 → 43 字符 base64url（无填充），满足 43-128 长度要求。 */
    fun newVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
