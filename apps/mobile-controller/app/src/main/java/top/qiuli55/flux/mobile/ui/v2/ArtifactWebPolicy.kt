package top.qiuli55.flux.mobile.ui.v2

import top.qiuli55.flux.mobile.data.ArtifactAsset
import java.net.URLDecoder

/**
 * 成品页面的加载策略，对齐 Claude Artifacts 的 CSP 语义：
 *
 * - 页面的 origin 固定为 `https://flux-artifact.local/`，不读取设备上的站点登录态；
 * - 只放行 Google Fonts 与五个公共 CDN（jsDelivr 仅 `/npm/` 路径）；
 * - 其它外部脚本 / 样式 / 字体 / 图片一律拦截；
 * - 相对路径优先按产物资产库解析，由已登录的 FluxApi 代取，避免把令牌暴露给页面。
 *
 * 纯函数，单测覆盖放行 / 拦截 / 资产匹配 / 视口注入。
 */
object ArtifactWebPolicy {

    const val ORIGIN = "https://flux-artifact.local/"

    private val allowedHosts = setOf(
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "cdnjs.cloudflare.com",
        "unpkg.com",
        "cdn.tailwindcss.com",
        "code.jquery.com",
        "cdn.jsdelivr.net",
    )

    /** 页面自身 origin / about:blank / data: 内联资源。 */
    fun isLocal(url: String): Boolean {
        val value = url.trim()
        return value.startsWith(ORIGIN, ignoreCase = true) ||
            value.equals("about:blank", ignoreCase = true) ||
            value.startsWith("data:", ignoreCase = true)
    }

    /** 外部请求放行白名单；jsDelivr 只允许 `/npm/` 下的包。 */
    fun isAllowedExternal(url: String): Boolean {
        val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme != "https") return false
        val host = parsed.host?.lowercase() ?: return false
        if (host !in allowedHosts) return false
        if (host == "cdn.jsdelivr.net") {
            val path = parsed.path.orEmpty()
            return path.startsWith("/npm/")
        }
        return true
    }

    fun shouldBlock(url: String): Boolean = !isLocal(url) && !isAllowedExternal(url)

    /** 页面里的相对路径（不含协议、不以 `#` 开头）；用于按资产库回源。 */
    fun isRelativeAsset(path: String): Boolean {
        val value = path.trim()
        if (value.isEmpty()) return false
        if (value.startsWith("#") || value.startsWith("javascript:", true)) return false
        return !value.contains("://") && !value.startsWith("//") &&
            !value.startsWith("data:", true) && !value.startsWith("about:", true)
    }

    /**
     * 相对路径 → 资产库条目：
     * 1. 目录无关，只看最后一段文件名；
     * 2. 大小写不敏感，兼容 `./logo.PNG`、`assets/logo.png?v=2`；
     * 3. 也接受直接写 32 位资产 id。
     *
     * 找不到时返回 null，由界面显示「图片缺失」占位。
     */
    fun assetForPath(path: String, assets: List<ArtifactAsset>): ArtifactAsset? {
        if (!isRelativeAsset(path)) return null
        var clean = path.substringBefore('#').substringBefore('?').trim()
        clean = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val name = clean.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (name.isEmpty()) return null
        return assets.firstOrNull { it.id.equals(name, ignoreCase = true) }
            ?: assets.firstOrNull { it.pathKey == name }
    }

    private val viewportMeta = Regex(
        """<meta[^>]+name=["']?viewport["']?[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 预览视口：手机用 `width=device-width`，桌面用 1280 宽，方便看响应式布局。
     *
     * 已有 viewport meta 就替换，否则插到 `<head>` 后；没有 `<head>` 时补一个。
     */
    fun viewportHtml(html: String, desktop: Boolean): String {
        val content = if (desktop) {
            "width=1280, initial-scale=1"
        } else {
            "width=device-width, initial-scale=1"
        }
        val meta = """<meta name="viewport" content="$content" />"""
        val source = html.ifBlank { "<!doctype html><html><head></head><body></body></html>" }
        if (viewportMeta.containsMatchIn(source)) {
            return source.replace(viewportMeta, meta)
        }
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(source)
        if (head != null) {
            val at = head.range.last + 1
            return source.substring(0, at) + "\n  " + meta + source.substring(at)
        }
        return "<!doctype html><html><head>$meta</head><body>$source</body></html>"
    }
}
