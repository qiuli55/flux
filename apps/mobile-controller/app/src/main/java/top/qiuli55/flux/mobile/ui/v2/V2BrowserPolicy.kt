package top.qiuli55.flux.mobile.ui.v2

import java.net.URI
import java.net.URLDecoder

/**
 * 内置浏览器的会话策略。
 *
 * 只放纯配置与纯函数，不直接调用 WebView / CookieManager，方便在 JVM 单测里锁住：
 *  - 站点会话：cookie 与 DOM Storage 持久化，允许第三方 cookie（登录 / OAuth 需要），
 *    允许 http(s) 与登录弹窗；
 *  - 离线预览：使用独立的合成域名 [OFFLINE_BASE_URL]，不放第三方 cookie、不允许外部网络，
 *    避免预览 HTML 读到站点登录态，也避免站点 cookie 被预览页面带出去。
 *
 * 两套策略共用同一份系统 CookieManager（Android 的 WebView 数据目录是进程级的，
 * 无法给两个同时存在的 WebView 指定不同目录），因此隔离靠「独立 origin + 阻断外部请求 +
 * 关闭第三方 cookie」实现；站点会话本身仍然持久化，进程被杀后登录态还在。
 */
internal enum class V2BrowserSession {
    /** 「我的」/ 对话链接进入的可持久化站点浏览。 */
    SITE,

    /** 设计稿等单文件 HTML 的离线预览。 */
    OFFLINE_PREVIEW,
}

internal data class V2BrowserPolicy(
    val session: V2BrowserSession,
    /** 仅对当前 WebView 生效；站点模式打开，登录/OAuth 弹窗需要。 */
    val acceptThirdPartyCookies: Boolean,
    /** 是否允许访问 http(s) 网络资源。离线预览关闭。 */
    val allowNetworkRequests: Boolean,
    /** 是否允许 window.open / OAuth 新窗口。离线预览关闭。 */
    val supportsMultipleWindows: Boolean,
    /** loadDataWithBaseURL 的合成基准地址，决定 localStorage / 同源边界。 */
    val baseUrl: String,
) {
    /** 站点会话的 cookie 与 localStorage 需要跨进程保留。 */
    val persistsCookies: Boolean get() = session == V2BrowserSession.SITE

    val isOfflinePreview: Boolean get() = session == V2BrowserSession.OFFLINE_PREVIEW
}

/** 离线预览的合成基准地址：和任何真实站点都不同源，localStorage 天然隔离。 */
internal const val OFFLINE_BASE_URL = "https://flux-preview.local/"

internal const val PREVIEW_HOST = "flux-preview.local"

/** 只信任 A 机与 B 机的自签证书；其它证书异常仍然拦截。 */
internal val TRUSTED_SSL_HOSTS = setOf("159.75.222.60", "115.159.125.254")

internal val SITE_BROWSER_POLICY = V2BrowserPolicy(
    session = V2BrowserSession.SITE,
    acceptThirdPartyCookies = true,
    allowNetworkRequests = true,
    supportsMultipleWindows = true,
    baseUrl = "about:blank",
)

internal val OFFLINE_PREVIEW_BROWSER_POLICY = V2BrowserPolicy(
    session = V2BrowserSession.OFFLINE_PREVIEW,
    acceptThirdPartyCookies = false,
    allowNetworkRequests = false,
    supportsMultipleWindows = false,
    baseUrl = OFFLINE_BASE_URL,
)

/** [html] 不为空就是离线预览，否则是可持久化的站点会话。 */
internal fun browserPolicyFor(html: String?): V2BrowserPolicy =
    if (html == null) SITE_BROWSER_POLICY else OFFLINE_PREVIEW_BROWSER_POLICY

/** 输入框里没写协议时默认补 https://；本机 IP 的 http 地址原样保留（单测覆盖）。 */
internal fun normalizeUrl(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return "about:blank"
    if (text.startsWith("http://") || text.startsWith("https://") || text == "about:blank") {
        return text
    }
    return "https://$text"
}

/** 页面真正要加载的地址；空白会话加载 about:blank。 */
internal fun browserStartUrl(raw: String?): String = normalizeUrl(raw.orEmpty())

/**
 * 地址栏显示文本：空白会话留空并展示占位提示，而不是把 about:blank 摆在地址栏里。
 */
internal fun browserAddressText(raw: String?): String {
    val text = raw?.trim().orEmpty()
    return if (text.isEmpty() || text == "about:blank") "" else normalizeUrl(text)
}

/** 「我的」里打开空白浏览会话：站点策略 + about:blank，之后可以输入任意网址。 */
internal fun blankBrowserSessionUrl(): String = "about:blank"

internal fun isTrustedSslHost(host: String?): Boolean =
    !host.isNullOrBlank() && host in TRUSTED_SSL_HOSTS

internal fun isWebPageScheme(scheme: String?): Boolean {
    val value = scheme?.lowercase().orEmpty()
    return value == "http" || value == "https"
}

/** 这些 scheme 由 WebView 自己处理，不交给系统浏览器 / 其它 App。 */
internal fun isWebViewInternalScheme(scheme: String?): Boolean {
    val value = scheme?.lowercase().orEmpty()
    return value in INTERNAL_SCHEMES
}

private val INTERNAL_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

/**
 * 非网页协议（tel / mailto / 第三方 App scheme）交给系统处理。
 * 离线预览一律不外跳，避免预览 HTML 拉起别的 App。
 */
internal fun shouldHandOffToSystem(policy: V2BrowserPolicy, scheme: String?): Boolean =
    !policy.isOfflinePreview && !isWebViewInternalScheme(scheme)

/**
 * 离线预览是否要拦截这条请求。
 *
 * 只放行合成 origin 自己的请求（相对资源）与内联 scheme；任何真实站点的 http(s)
 * 请求都返回空响应，既不把站点 cookie 带出去，也不让预览页偷偷联网。
 */
internal fun shouldBlockPreviewRequest(
    policy: V2BrowserPolicy,
    scheme: String?,
    host: String?,
): Boolean {
    if (policy.allowNetworkRequests) return false
    val value = scheme?.lowercase().orEmpty()
    return when (value) {
        "http", "https" -> !host.equals(PREVIEW_HOST, ignoreCase = true)
        "about", "data", "blob", "javascript" -> false
        else -> true
    }
}

// ------------------------------------------------------------------ User-Agent

/**
 * 站点会话默认桌面 UA：很多登录 / OAuth 页面在手机 UA 下会跳去「打开 App」或给残缺版，
 * 桌面 UA 能直接在 App 内完成登录。字符串集中放在 Policy，方便单测锁住。
 *
 * 离线预览不改 UA：它是本地渲染的设计稿，不需要站点兼容逻辑。
 */
internal const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

internal const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** 返回 null 表示保持 WebView 系统默认 UA（离线预览）。 */
internal fun browserUserAgent(policy: V2BrowserPolicy, desktopMode: Boolean): String? =
    when {
        policy.isOfflinePreview -> null
        desktopMode -> DESKTOP_USER_AGENT
        else -> MOBILE_USER_AGENT
    }

// ------------------------------------------------------------------ 下载

/** 解析出只可能是 http(s) 的 host；非网页 URL 返回 null。java.net.URI，便于 JVM 单测。 */
internal fun httpUrlHost(url: String?): String? {
    val text = url?.trim().orEmpty()
    if (text.isEmpty()) return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    return uri.host?.takeIf { it.isNotBlank() }?.lowercase()
}

/**
 * 只有站点会话的 http(s) 下载才交给系统 DownloadManager。
 * data / blob / file / content 等一律不接：它们可以被用来把任意本地内容写到磁盘。
 */
internal fun isDownloadUrlAllowed(policy: V2BrowserPolicy, url: String?): Boolean {
    if (policy.isOfflinePreview) return false
    val host = httpUrlHost(url) ?: return false
    return host != PREVIEW_HOST
}

/** 下载文件名：Content-Disposition 优先，其次 URL 路径；都不可用时给 download。 */
internal fun downloadFileName(
    contentDisposition: String?,
    url: String?,
    mimeType: String?,
): String {
    val raw = parseContentDispositionName(contentDisposition)
        ?: urlPathFileName(url)
        ?: "download"
    val cleaned = sanitizeFileName(raw).ifBlank { "download" }
    return ensureExtension(cleaned, mimeType)
}

private fun parseContentDispositionName(header: String?): String? {
    val text = header?.trim().orEmpty()
    if (text.isEmpty()) return null
    // RFC 5987: filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf
    val encoded = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
        .find(text)
    if (encoded != null) {
        val charset = encoded.groupValues[1].ifBlank { "UTF-8" }
        val value = encoded.groupValues[2].trim().trim('"')
        return runCatching { URLDecoder.decode(value, charset) }.getOrDefault(value)
    }
    Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.let { return it.groupValues[1] }
    Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.let { return it.groupValues[1].trim().trim('"') }
    return null
}

private fun urlPathFileName(url: String?): String? {
    val text = url?.trim().orEmpty()
    if (text.isEmpty()) return null
    val path = runCatching { URI(text).path }.getOrNull().orEmpty()
    return path.substringAfterLast('/').takeIf { it.isNotBlank() }
}

private fun sanitizeFileName(raw: String): String {
    // 去掉任何路径前缀，避免 ../ 或绝对路径被 DownloadManager 当成目录
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = buildString {
        for (ch in base) {
            when {
                ch.code < 0x20 || ch.code == 0x7f -> append(' ')
                ch == '"' || ch == '<' || ch == '>' || ch == '|' || ch == ':' || ch == '*' || ch == '?' -> append('_')
                else -> append(ch)
            }
        }
    }.trim()
    if (cleaned == "." || cleaned == "..") return ""
    return cleaned.take(120)
}

private val EXTENSION_BY_MIME = mapOf(
    "application/pdf" to "pdf",
    "application/zip" to "zip",
    "application/json" to "json",
    "application/octet-stream" to "bin",
    "application/vnd.android.package-archive" to "apk",
    "text/plain" to "txt",
    "text/csv" to "csv",
    "text/html" to "html",
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/gif" to "gif",
    "image/webp" to "webp",
    "image/svg+xml" to "svg",
    "audio/mpeg" to "mp3",
    "video/mp4" to "mp4",
)

private fun ensureExtension(name: String, mimeType: String?): String {
    // name 已经 sanitize 掉路径分隔符，这里只看有没有扩展名
    if (name.contains('.')) return name
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val ext = EXTENSION_BY_MIME[mime] ?: return name
    return "$name.$ext"
}

// ------------------------------------------------------------------ 返回键

/** 硬件 / 手势返回在浏览器页里的处理顺序。 */
internal enum class V2BrowserBackAction {
    CLOSE_JS_DIALOG,
    CLOSE_PERMISSION,
    CLOSE_POPUP,
    CLOSE_FIND,
    CLOSE_HISTORY,
    WEBVIEW_BACK,
    EXIT,
}

/**
 * 返回键优先级：先关掉最上层的 App 内弹层，再让 WebView 后退，最后才退出浏览器页。
 * 纯函数，保证「硬件返回不会直接把正在登录的页面整个关掉」。
 */
internal fun decideBrowserBack(
    jsDialogOpen: Boolean = false,
    permissionOpen: Boolean = false,
    popupOpen: Boolean = false,
    findOpen: Boolean = false,
    historyOpen: Boolean = false,
    canGoBack: Boolean = false,
): V2BrowserBackAction = when {
    jsDialogOpen -> V2BrowserBackAction.CLOSE_JS_DIALOG
    permissionOpen -> V2BrowserBackAction.CLOSE_PERMISSION
    popupOpen -> V2BrowserBackAction.CLOSE_POPUP
    findOpen -> V2BrowserBackAction.CLOSE_FIND
    historyOpen -> V2BrowserBackAction.CLOSE_HISTORY
    canGoBack -> V2BrowserBackAction.WEBVIEW_BACK
    else -> V2BrowserBackAction.EXIT
}

// ------------------------------------------------------------------ 权限 / 历史

/** 把 WebView 的权限资源翻成给用户看的名字；未知资源也明确说「页面请求的权限」。 */
internal fun webPermissionLabel(resources: Array<out String>?): String {
    val names = resources?.toList().orEmpty().mapNotNull { resource ->
        when (resource) {
            "android.webkit.resource.VIDEO_CAPTURE" -> "摄像头"
            "android.webkit.resource.AUDIO_CAPTURE" -> "麦克风"
            "android.webkit.resource.PROTECTED_MEDIA_ID" -> "受保护媒体"
            else -> null
        }
    }.distinct()
    return if (names.isEmpty()) "页面请求的权限" else names.joinToString("、")
}

/** 站点历史只记录真实网页；离线预览、about:blank、file/data 都不写。 */
internal fun shouldRecordHistory(policy: V2BrowserPolicy, url: String?): Boolean {
    if (!policy.persistsCookies) return false
    val host = httpUrlHost(url) ?: return false
    return host != PREVIEW_HOST
}
