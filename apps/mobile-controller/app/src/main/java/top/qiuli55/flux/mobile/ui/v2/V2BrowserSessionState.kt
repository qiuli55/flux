package top.qiuli55.flux.mobile.ui.v2

import android.content.Context
import android.os.Bundle

/**
 * 站点会话的页面快照。
 *
 * 退出浏览器页会销毁 WebView（保住内存和后台行为），但用户可能刚登录完、或表单
 * 才填到一半就被对话打断。离开前把 [android.webkit.WebView.saveState] 的 Bundle
 * 留在进程内，下次从空白入口进入时 restoreState，历史与当前页尽量原样回来。
 *
 * 这个对象只服务站点会话；离线预览不写入也不读取。Bundle 不落盘，进程被杀后退回
 * [V2BrowserSettingsStore] 里保存的最后 URL，登录态本身仍由已 flush 的 cookie 保证。
 */
internal object V2BrowserRetainedState {
    var siteUrl: String? = null
        private set
    var siteState: Bundle? = null
        private set

    fun remember(url: String?, state: Bundle?) {
        siteUrl = url
        siteState = state
    }

    fun clear() {
        siteUrl = null
        siteState = null
    }
}

/**
 * 浏览器的小块持久偏好：桌面 / 移动 UA 选择、最后一次站点页面。
 *
 * 和「最近访问」共用 flux_browser 这个 SharedPreferences 文件，避免为两个字符串
 * 再引入新的存储。UA 选择必须跨进入 / 退出保留，否则用户每次进浏览器都会被重置
 * 成桌面版。
 */
internal class V2BrowserSettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flux_browser", Context.MODE_PRIVATE)

    /** 默认桌面 UA：登录 / OAuth 页在移动 UA 下经常跳「打开 App」。 */
    fun desktopMode(): Boolean = prefs.getBoolean(KEY_DESKTOP_MODE, true)

    fun saveDesktopMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DESKTOP_MODE, enabled).apply()
    }

    /** 只记真实网页；about:blank 之类的空会话不覆盖上一次的页面。 */
    fun lastSiteUrl(): String? =
        restorableBrowserUrl(SITE_BROWSER_POLICY, prefs.getString(KEY_LAST_URL, null))

    fun saveLastSiteUrl(url: String?) {
        val value = restorableBrowserUrl(SITE_BROWSER_POLICY, url) ?: return
        prefs.edit().putString(KEY_LAST_URL, value).apply()
    }

    private companion object {
        const val KEY_DESKTOP_MODE = "desktop_mode"
        const val KEY_LAST_URL = "last_site_url"
    }
}
