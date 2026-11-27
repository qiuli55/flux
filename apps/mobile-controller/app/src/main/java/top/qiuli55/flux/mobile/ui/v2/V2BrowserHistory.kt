package top.qiuli55.flux.mobile.ui.v2

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 内置浏览器的「最近访问」。
 *
 * 只服务站点会话：记录 URL、标题和时间，按访问时间倒序去重，最多保留
 * [BROWSER_HISTORY_LIMIT] 条。不是书签系统，没有分类 / 编辑 / 同步；
 * 离线预览不写入这里。
 *
 * 存储用 SharedPreferences + JSON，避免为这点小状态再起一个 DataStore。
 * 编解码抽成纯函数，便于在 JVM 单测里锁住「去重、倒序、有上限」。
 */
@Serializable
internal data class BrowserHistoryEntry(
    val url: String,
    val title: String = "",
    val visitedAt: Long = 0L,
)

internal const val BROWSER_HISTORY_LIMIT = 50

private val historyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val historySerializer = ListSerializer(BrowserHistoryEntry.serializer())

/** 新访问插到最前；同一 URL 只保留最新一条，超过上限截断。 */
internal fun addBrowserHistory(
    existing: List<BrowserHistoryEntry>,
    entry: BrowserHistoryEntry,
    limit: Int = BROWSER_HISTORY_LIMIT,
): List<BrowserHistoryEntry> {
    if (entry.url.isBlank() || entry.url == "about:blank") return existing
    if (limit <= 0) return emptyList()
    return (listOf(entry) + existing.filterNot { it.url == entry.url })
        .take(limit)
}

/** 标题为空时用 host 兜底，列表里不会出现一整条空白。 */
internal fun browserHistoryTitle(entry: BrowserHistoryEntry): String {
    val title = entry.title.trim()
    if (title.isNotEmpty()) return title
    return httpUrlHost(entry.url) ?: entry.url
}

internal fun encodeBrowserHistory(entries: List<BrowserHistoryEntry>): String =
    runCatching { historyJson.encodeToString(historySerializer, entries) }
        .getOrDefault("[]")

/** 存储损坏 / 旧格式时退回空列表，不让浏览页因为历史记录打不开。 */
internal fun decodeBrowserHistory(raw: String?): List<BrowserHistoryEntry> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    return runCatching { historyJson.decodeFromString(historySerializer, text) }
        .getOrDefault(emptyList())
        .filter { it.url.isNotBlank() }
        .take(BROWSER_HISTORY_LIMIT)
}

internal class V2BrowserHistoryStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flux_browser", Context.MODE_PRIVATE)

    fun load(): List<BrowserHistoryEntry> = decodeBrowserHistory(prefs.getString(KEY_HISTORY, null))

    fun save(entries: List<BrowserHistoryEntry>) {
        prefs.edit().putString(KEY_HISTORY, encodeBrowserHistory(entries)).apply()
    }

    private companion object {
        const val KEY_HISTORY = "history"
    }
}
