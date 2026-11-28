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
data class MailStatus(
    @SerialName("enabled") val enabled: Boolean = false,
)

@Serializable
data class WebSessionResponse(
    val url: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
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

/**
 * 账号云登录相关接口。
 *
 * 抽成接口只是为了让登录页能在 JVM 测试里注入假实现；生产环境仍然只用 [CloudApi]。
 */
interface CloudAuthApi {
    fun oauthStartUrl(
        provider: String,
        redirect: String = "flux://auth",
        codeChallenge: String = "",
    ): String

    suspend fun login(identifier: String, password: String): AccountSession

    suspend fun exchange(code: String, codeVerifier: String = ""): AccountSession

    /** 账号云当前是否允许发送邮件；网络失败由调用方按「不可用」处理。 */
    suspend fun mailStatus(): Boolean

    suspend fun sendRegisterCode(email: String)

    /** 注册并直接换回会话；邮件服务关闭时 [code] 可以省略。 */
    suspend fun register(
        email: String,
        username: String,
        password: String,
        code: String? = null,
    ): AccountSession

    suspend fun forgotPassword(email: String)

    /** 重置密码只返回服务端消息，不会带来登录态。 */
    suspend fun resetPassword(email: String, code: String, newPassword: String): String
}

/** 账号云 REST 客户端：登录 / 换取 / 续期 / 设备列表 / 注册与找回密码。 */
class CloudApi(private val baseUrl: String = AccountSession.DEFAULT_CLOUD_URL) : CloudAuthApi {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun login(identifier: String, password: String): AccountSession =
        toSession(
            postJson(
                "api/auth/login/token",
                buildJsonObject {
                    put("identifier", identifier.trim())
                    put("password", password)
                }.toString(),
            ),
        )

    override suspend fun exchange(code: String, codeVerifier: String): AccountSession =
        toSession(
            postJson(
                "api/auth/exchange",
                buildJsonObject {
                    put("code", code.trim())
                    if (codeVerifier.isNotBlank()) put("code_verifier", codeVerifier)
                }.toString(),
            ),
        )

    override suspend fun mailStatus(): Boolean =
        json.decodeFromString<MailStatus>(getJson("api/auth/mail-status")).enabled

    override suspend fun sendRegisterCode(email: String) {
        postJson(
            "api/auth/register/send-code",
            buildJsonObject { put("email", email.trim()) }.toString(),
        )
    }

    override suspend fun register(
        email: String,
        username: String,
        password: String,
        code: String?,
    ): AccountSession = toSession(
        postJson(
            "api/auth/register/token",
            buildJsonObject {
                put("email", email.trim())
                put("username", username.trim())
                put("password", password)
                if (!code.isNullOrBlank()) put("code", code.trim())
            }.toString(),
        ),
    )

    override suspend fun forgotPassword(email: String) {
        postJson(
            "api/auth/password/forgot",
            buildJsonObject { put("email", email.trim()) }.toString(),
        )
    }

    override suspend fun resetPassword(
        email: String,
        code: String,
        newPassword: String,
    ): String = postJson(
        "api/auth/password/reset",
        buildJsonObject {
            put("email", email.trim())
            put("code", code.trim())
            put("new_password", newPassword)
        }.toString(),
    )

    /** 用当前 access token 换一次性网页登录地址（内置浏览器打开）。 */
    suspend fun webSession(accessToken: String): String {
        val text = getJson("api/auth/web-session", accessToken)
        val dto = try {
            json.decodeFromString<WebSessionResponse>(text)
        } catch (_: Exception) {
            throw CloudApiException("invalid_response", "账号云返回的登录链接无法解析")
        }
        val url = dto.url.trim()
        if (url.isBlank()) {
            throw CloudApiException("invalid_response", "账号云没有返回有效的登录链接")
        }
        return url
    }

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

    override fun oauthStartUrl(
        provider: String,
        redirect: String,
        codeChallenge: String,
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

    private suspend fun getJson(path: String, accessToken: String = ""): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/$path")
                .header("Accept", "application/json")
                .get()
            if (accessToken.isNotBlank()) {
                builder.header("Authorization", "Bearer $accessToken")
            }
            execute(builder.build())
        }

    private fun execute(request: Request): String {
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) return text
            throw parseCloudError(text, it.code)
        }
    }
}

/**
 * 账号云错误体解析：复用 FluxApi 的严格解析，兼容
 * `{"detail":"中文"}`、`{"detail":[{"msg":"中文"}]}` 与 `{"message":"中文"}`。
 */
internal fun parseCloudError(text: String, status: Int): CloudApiException {
    val info = parseApiError(text, status)
    return CloudApiException(info.code, info.message)
}

/** OAuth 回跳（flux://auth?code=...）把授权码交给登录页。 */
object CloudAuthBus {
    val codes = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun publish(code: String) {
        if (code.isNotBlank()) codes.tryEmit(code)
    }
}
