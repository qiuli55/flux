package top.qiuli55.flux.mobile.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 清单解析统一入口：新增字段不影响旧客户端。 */
val UpdateJson = Json { ignoreUnknownKeys = true }

/**
 * B 机分发节点上的更新清单（/flux-mobile/latest.json）。
 *
 * 字段与发版脚本写入的 JSON 一一对应；缺字段时用默认值兜底，避免清单升级导致旧客户端崩溃。
 */
@Serializable
data class UpdateManifest(
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("version_name") val versionName: String = "",
    @SerialName("apk_url") val apkUrl: String = "",
    val sha256: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    @SerialName("published_at") val publishedAt: String = "",
    val notes: List<String> = emptyList(),
    @SerialName("min_version_code") val minVersionCode: Int = 0,
) {
    /**
     * 清单是否值得安装：版本号必须递增，且下载地址必须是 HTTPS。
     * 只认 https 是为了防止清单被篡改成明文地址后装进被替换的包。
     */
    fun isNewerThan(currentCode: Int): Boolean =
        versionCode > currentCode && apkUrl.startsWith("https://")

    /** 低于最低支持版本时不给「稍后」，只留更新。 */
    fun isForcedFor(currentCode: Int): Boolean = minVersionCode > currentCode
}

/**
 * 自动弹层策略：跳过的版本不再打扰；同一个版本一天最多弹一次。
 *
 * 手动检查不走这里（用户主动点了就要给结果），强制更新也不受「跳过」限制。
 */
fun shouldAutoPrompt(
    manifest: UpdateManifest,
    currentCode: Int,
    skippedCode: Int,
    lastPromptDay: String,
    today: String,
): Boolean {
    if (!manifest.isNewerThan(currentCode)) return false
    val forced = manifest.isForcedFor(currentCode)
    if (!forced && manifest.versionCode <= skippedCode) return false
    return lastPromptDay != today
}

/** 下载进度百分比；总量未知时保持 0，避免出现负数或超过 100。 */
fun downloadPercent(done: Long, total: Long): Int {
    if (done <= 0 || total <= 0) return 0
    return (done * 100 / total).toInt().coerceIn(0, 100)
}
