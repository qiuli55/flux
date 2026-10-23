package top.qiuli55.flux.mobile.update

import android.util.Log
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 更新清单拉取器。
 *
 * 只请求 B 机静态分发地址，不带任何令牌；超时放宽到 15 秒，
 * 失败只记日志并由调用方决定是否提示，绝不阻塞启动与主流程。
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val manifestUrl: String = MANIFEST_URL,
) {
    fun fetch(): Result<UpdateManifest> = runCatching {
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("更新清单返回 HTTP ${response.code}")
            val body = response.body?.string() ?: error("更新清单为空")
            json.decodeFromString<UpdateManifest>(body)
        }
    }.onFailure { Log.w(TAG, "拉取更新清单失败: ${it.message}") }

    companion object {
        /** B 机 443 静态分发入口（nginx alias 到 /opt/downloads/flux-mobile）。 */
        const val MANIFEST_URL = "https://flux.app.qiuli55.top/flux-mobile/latest.json"

        private const val TAG = "FluxUpdate"
        private val json = Json { ignoreUnknownKeys = true }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
