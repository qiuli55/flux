package top.qiuli55.flux.mobile.data

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 账号云（B 机）的会话与设备模型。
 *
 * 与「服务器地址 + 令牌」的手动连接并存：账号模式下 baseUrl 指向云端中转
 * `/api/devices/{device_id}/p`，token 用账号云的 access token，后端本地验签。
 */
@Serializable
data class AccountSession(
    val cloudUrl: String = DEFAULT_CLOUD_URL,
    val accessToken: String = "",
    val refreshToken: String = "",
    val username: String = "",
    val displayName: String = "",
    val email: String = "",
    val expiresAtEpochSec: Long = 0,
    val deviceId: String = "",
    val deviceName: String = "",
) {
    val isSignedIn: Boolean get() = accessToken.isNotBlank()
    val hasDevice: Boolean get() = deviceId.isNotBlank()

    /** 提前 2 分钟续期，避免刚进页面就被判过期。 */
    val isExpiringSoon: Boolean
        get() = expiresAtEpochSec > 0 &&
            expiresAtEpochSec - System.currentTimeMillis() / 1000 < 120

    /** 账号模式下 Flux 的基址：云端中转入口。 */
    val proxyBaseUrl: String
        get() = "${cloudUrl.trimEnd('/')}/api/devices/$deviceId/p"

    companion object {
        const val DEFAULT_CLOUD_URL = "https://flux.qiuli55.top"
    }
}

@Serializable
data class CloudUser(
    val id: Int = 0,
    val email: String? = null,
    val username: String = "",
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class CloudTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
    val user: CloudUser = CloudUser(),
)

@Serializable
data class DeviceInfo(
    @SerialName("device_id") val deviceId: String = "",
    val name: String = "",
    val platform: String = "",
    val version: String? = null,
    val status: String = "offline",
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
) {
    val isOnline: Boolean get() = status == "online"
    val platformLabel: String
        get() = when (platform) {
            "windows" -> "Windows"
            "linux" -> "Linux"
            "server" -> "服务器"
            "android" -> "Android"
            else -> platform.ifBlank { "未知" }
        }
}

class CloudApiException(
    val code: String,
    override val message: String,
) : Exception(message)

/** 账号云 REST 客户端：登录 / 换取 / 续期 / 设备列表。 */
class CloudApi(private val baseUrl: String = AccountSession.DEFAULT_CLOUD_URL) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun login(identifier: String, password: String): AccountSession =
        toSession(
            postJson(
                "api/auth/login/token",
                buildJsonObject {
                    put("identifier", identifier.trim())
                    put("password", password)
                }.toString(),
            ),
        )

    suspend fun exchange(code: String, codeVerifier: String = ""): AccountSession =
        toSession(
            postJson(
                "api/auth/exchange",
                buildJsonObject {
                    put("code", code.trim())
                    if (codeVerifier.isNotBlank()) put("code_verifier", codeVerifier)
                }.toString(),
            ),
        )

    suspend fun refresh(refreshToken: String): AccountSession =
        toSession(
            postJson(
                "api/auth/token",
                buildJsonObject { put("refresh_token", refreshToken) }.toString(),
            ),
        )

    suspend fun devices(accessToken: String): List<DeviceInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/devices")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        json.decodeFromString(execute(request))
    }

    fun oauthStartUrl(
        provider: String,
        redirect: String = "flux://auth",
        codeChallenge: String = "",
    ): String {
        val base = "${baseUrl.trimEnd('/')}/api/auth/oauth/$provider/start?next=" +
            URLEncoder.encode(redirect, "UTF-8")
        if (codeChallenge.isBlank()) return base
        return "$base&code_challenge=${URLEncoder.encode(codeChallenge, "UTF-8")}" +
            "&code_challenge_method=S256"
    }

    private fun toSession(text: String): AccountSession {
        val dto = json.decodeFromString<CloudTokenResponse>(text)
        val now = System.currentTimeMillis() / 1000
        return AccountSession(
            cloudUrl = baseUrl,
            accessToken = dto.accessToken,
            refreshToken = dto.refreshToken,
            username = dto.user.username,
            displayName = dto.user.displayName ?: dto.user.username,
            email = dto.user.email ?: "",
            expiresAtEpochSec = now + dto.expiresIn,
        )
    }

    private suspend fun postJson(path: String, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/$path")
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        execute(request)
    }

    private fun execute(request: Request): String {
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) return text
            val detail = runCatching {
                json.decodeFromString<Map<String, String>>(text)["detail"]
            }.getOrNull()
            val code = when (it.code) {
                401 -> "unauthenticated"
                403 -> "forbidden"
                else -> "http_${it.code}"
            }
            throw CloudApiException(code, detail ?: "请求失败（HTTP ${it.code}）")
        }
    }
}

/** OAuth 回跳（flux://auth?code=...）把授权码交给登录页。 */
object CloudAuthBus {
    val codes = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun publish(code: String) {
        if (code.isNotBlank()) codes.tryEmit(code)
    }
}
