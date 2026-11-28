package top.qiuli55.flux.mobile.data

import android.content.Context
import android.os.Build
import android.os.Process
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import java.security.MessageDigest

/**
 * 网站登录态按 Flux 账号隔离。
 *
 * WebView 的 cookie / DOM Storage 本来就在磁盘上（同一应用数据目录），但那个目录是
 * **应用级**的：同一台设备切换 Flux 账号时，B 账号会读到 A 账号在站点上的登录态。
 * 这里用官方 [WebView.setDataDirectorySuffix] 给每个 Flux 账号一个独立数据目录，
 * 在 [FluxApp][top.qiuli55.flux.mobile.FluxApp] 里、任何 WebView 初始化之前设置一次。
 *
 * 约束与取舍：
 *  - suffix 只能用稳定、不可逆的账号键计算，绝不能把 access token 写进目录名；
 *  - API 28+ 才支持 suffix。运行中切换账号时，旧目录无法热切换，只能先 flush、
 *    再安全重启进程，让新 suffix 在下次启动时生效；
 *  - API 26 / 27 没有 suffix API，退化为「切换账号时清空 cookie 与 DOM Storage」，
 *    并在切换后记录新的目标账号，避免下一次再清一遍。
 *  - 绝不与系统 Chrome 共用目录：数据始终在 Flux 自己的 WebView 目录里。
 */

internal const val WEBVIEW_GUEST_PROFILE = "guest"

/**
 * 账号键：优先用账号云稳定 userId，其次 username / email；都不是 token，
 * 且做哈希后只保留不可逆摘要。
 */
internal fun webViewProfileKey(userId: Long, username: String?, email: String?): String {
    val identity = when {
        userId > 0 -> "id:$userId"
        else -> username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return WEBVIEW_GUEST_PROFILE
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "acct-${hex.take(32)}"
}

/** 没有 userId 的旧账号 / 测试便捷入口。 */
internal fun webViewProfileKey(username: String?, email: String?): String =
    webViewProfileKey(0L, username, email)

/**
 * WebView 数据目录 suffix。只保留小写字母 / 数字 / 短横线 / 下划线，且永不为空；
 * 这样即使调用方传入了意外键，也不会触发 setDataDirectorySuffix 的参数校验异常。
 */
internal fun webViewDataDirectorySuffix(profileKey: String): String {
    val cleaned = profileKey
        .lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "-")
        .trim('-')
        .take(48)
        .ifBlank { WEBVIEW_GUEST_PROFILE }
    return "flux-$cleaned"
}

internal enum class WebViewProfileAction {
    /** 账号没变，继续用当前数据目录。 */
    KEEP,

    /** 支持 suffix：重启进程后生效。 */
    RESTART,

    /** 不支持 suffix：清空当前 WebView 的 cookie / DOM Storage / 缓存。 */
    CLEAR,
}

/**
 * 账号 profile 变化时的动作。纯函数，单测锁住「同账号绝不重启」「换账号必须隔离或清空」。
 */
internal fun decideWebViewProfileAction(
    installedKey: String,
    targetKey: String,
    supportsDataDirectorySuffix: Boolean,
): WebViewProfileAction = when {
    installedKey == targetKey -> WebViewProfileAction.KEEP
    supportsDataDirectorySuffix -> WebViewProfileAction.RESTART
    else -> WebViewProfileAction.CLEAR
}

/** 当前进程是否支持按账号切换 WebView 数据目录（API 28+）。 */
internal fun supportsWebViewDataDirectorySuffix(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

/**
 * 同步落盘的「目标 WebView profile」。账号保存在 DataStore（异步），而
 * `WebView.setDataDirectorySuffix` 必须在 Application.onCreate 里同步读取，
 * 因此这里单独用 SharedPreferences 记录账号键；只存哈希，不存账号明文或令牌。
 */
internal class WebViewProfileStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flux_webview_profile", Context.MODE_PRIVATE)

    fun currentKey(): String =
        prefs.getString(KEY_PROFILE, WEBVIEW_GUEST_PROFILE)
            ?.takeIf { it.isNotBlank() }
            ?: WEBVIEW_GUEST_PROFILE

    fun saveCurrentKey(key: String) {
        // commit 而非 apply：切账号后马上可能重启进程，必须确保新键已经落盘。
        prefs.edit().putString(KEY_PROFILE, key).commit()
    }

    private companion object {
        const val KEY_PROFILE = "profile_key"
    }
}

/**
 * WebView profile 运行时。
 *
 * [install] 只在 FluxApp.onCreate 调用一次；[restartIfProfileChanged] 由根容器在
 * 账号态变化时调用——先 flush 站点 cookie，再重启应用，让新数据目录在干净进程里生效。
 */
internal object WebViewProfileRuntime {
    private var appContext: Context? = null
    private var installedKey: String = WEBVIEW_GUEST_PROFILE
    private var supportsSuffix: Boolean = false
    private var switching: Boolean = false

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        installedKey = WebViewProfileStore(app).currentKey()
        supportsSuffix = supportsWebViewDataDirectorySuffix()
        if (supportsSuffix) {
            // 设置失败（例如 WebView 已被更早初始化）时不要假装隔离已生效，
            // 退回「切账号时清空」的兜底逻辑。
            supportsSuffix = runCatching {
                WebView.setDataDirectorySuffix(webViewDataDirectorySuffix(installedKey))
            }.isSuccess
        }
    }

    /** 账号态变化后调用；返回是否真的执行了隔离动作（重启或清空）。 */
    fun restartIfProfileChanged(): Boolean {
        val context = appContext ?: return false
        if (switching) return false
        val targetKey = WebViewProfileStore(context).currentKey()
        return when (
            decideWebViewProfileAction(installedKey, targetKey, supportsSuffix)
        ) {
            WebViewProfileAction.KEEP -> false
            WebViewProfileAction.RESTART -> {
                switching = true
                flushCookies()
                restartProcess(context)
                true
            }

            WebViewProfileAction.CLEAR -> {
                switching = true
                clearWebViewData(context)
                installedKey = targetKey
                // 清完磁盘后再重启一次，确保内存里的 CookieManager / WebStorage 也不残留。
                // 重启用同一数据目录（API 26 / 27 没有 suffix），隔离靠「清空」而不是分目录。
                switching = false
                restartProcess(context)
                true
            }
        }
    }

    private fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    /** 先 flush，再清空 cookie / DOM Storage / WebView 缓存（API 26 / 27 的兜底）。 */
    private fun clearWebViewData(context: Context) {
        flushCookies()
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching {
            // 这里在没有任何 WebView 时创建一个仅用于清缓存，随后立刻销毁；不会再改数据目录。
            WebView(context).apply {
                clearCache(true)
                clearFormData()
                destroy()
            }
        }
    }

    /**
     * 安全重启：先拉起当前 Activity 的启动 Intent，再结束本进程。
     * 直接 kill 而不重建会让用户停在桌面；起不来时保持原进程，避免把 App 关死。
     */
    private fun restartProcess(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch == null) {
            switching = false
            return
        }
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val started = runCatching { context.startActivity(launch) }.isSuccess
        if (!started) {
            switching = false
            return
        }
        // 给系统一点时间把新 Activity 的启动请求排上队，再结束本进程；
        // 直接 kill 有可能把刚发出的启动请求一起丢掉。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { Process.killProcess(Process.myPid()) },
            300L,
        )
    }
}
