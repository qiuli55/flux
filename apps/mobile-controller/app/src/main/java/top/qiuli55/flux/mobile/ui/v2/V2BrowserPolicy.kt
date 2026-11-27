package top.qiuli55.flux.mobile.ui.v2

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
