package top.qiuli55.flux.mobile.ui.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.qiuli55.flux.mobile.data.FluxApiException

/**
 * 「提取到创作」的纯逻辑。
 *
 * 放在 WebView / 网络调用之外，方便 JVM 单测锁住：
 *  - 溢出菜单的两项文案与请求体字段；
 *  - 离线预览不提供提取（不把预览 HTML 变成创作内容）；
 *  - 失败 / 无网络时给中文说明，且只有从对话进入的浏览器才提供「发回当前对话」。
 */

internal const val EXTRACT_DOC_LABEL = "提取为文档"
internal const val EXTRACT_PAGE_LABEL = "保存为页面"

internal enum class V2BrowserExtractType(val apiType: String, val menuLabel: String) {
    DOC("doc", EXTRACT_DOC_LABEL),
    PAGE("page", EXTRACT_PAGE_LABEL),
}

/** 一次提取的弹层状态：选择是否发回对话 → 加载 → 成功 / 失败可重试。 */
internal data class V2BrowserExtractUi(
    val type: V2BrowserExtractType,
    val sendToChat: Boolean,
    val loading: Boolean = false,
    val error: String? = null,
    val artifactId: String? = null,
    val fallback: Boolean = false,
    val sentToChat: Boolean = false,
    val note: String? = null,
)

/** 站点会话才能提取；离线预览的合成页面不属于可发布的网页。 */
internal fun browserCanExtract(policy: V2BrowserPolicy): Boolean = !policy.isOfflinePreview

/** 只有从已有对话进入的浏览器才知道把卡片发回哪段对话。 */
internal fun browserCanSendExtractToChat(policy: V2BrowserPolicy, taskId: String?): Boolean =
    browserCanExtract(policy) && !taskId.isNullOrBlank()

/**
 * WebView.evaluateJavascript 返回的是 JS 字面量：字符串会被 JSON 引号包住。
 * 只接受字符串结果；null / 非字符串 / 解析失败都当作「拿不到 DOM」，交给服务端抓取。
 */
internal fun decodeEvaluatedJavascriptString(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "null" || text == "undefined") return null
    return runCatching {
        (Json.parseToJsonElement(text) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** 客户端 HTML 上限；超过就放弃上传，改由服务端按 URL 抓取。 */
internal const val MAX_EXTRACT_HTML_CHARS = 8 * 1024 * 1024

internal fun browserExtractHtml(raw: String?): String? =
    decodeEvaluatedJavascriptString(raw)?.takeIf { it.length <= MAX_EXTRACT_HTML_CHARS }

internal fun browserExtractErrorText(error: Exception): String = when (error) {
    is FluxApiException -> when (error.code) {
        "network_error" -> EXTRACT_NETWORK_HINT
        "empty_response", "empty_data", "invalid_response" ->
            "服务端返回异常，提取没有完成。请稍后重试。"
        else -> error.message.takeIf { it.isNotBlank() } ?: "提取失败，请稍后重试。"
    }

    else -> error.message?.takeIf { it.isNotBlank() } ?: "提取失败，请稍后重试。"
}

/** 提取成功文案：降级时明确告诉用户只保存了链接和标题，而不是假装抽到了正文。 */
internal fun browserExtractSuccessText(fallback: Boolean, sentToChat: Boolean): String = buildString {
    if (fallback) {
        append("页面正文没有提取成功，已保存标题、链接和摘录。")
    } else {
        append("已提取正文并保存到「创作」。")
    }
    if (sentToChat) append("同时已发回当前对话。")
}

internal fun browserExtractTitle(type: V2BrowserExtractType): String = type.menuLabel

/** 没有可提取链接时的一句提示；离线预览走不到这里。 */
internal const val EXTRACT_NO_LINK_HINT = "当前页面没有可提取的链接"
internal const val EXTRACT_NETWORK_HINT = "网络不可用，提取没有完成。检查网络后再试一次。"
