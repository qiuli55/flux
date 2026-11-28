package top.qiuli55.flux.mobile.ui.v2

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import top.qiuli55.flux.mobile.data.FluxEnv
import top.qiuli55.flux.mobile.data.GitHubLinkBus

/** 网页 JS 对话框的类型；三种都必须在 App 内应答，否则页面会卡住。 */
private enum class V2JsDialogKind { ALERT, CONFIRM, PROMPT }

/**
 * 一个待用户应答的 JS 对话框。把结果回调直接包进来，弹窗关闭后只调用一次，
 * 避免 WebView 的 JsResult 泄漏或页面停在空白 prompt 上。
 */
private class V2JsDialogState(
    val kind: V2JsDialogKind,
    val message: String,
    val defaultValue: String,
    val onConfirm: (String?) -> Unit,
    val onCancel: () -> Unit,
)

/** 摄像头 / 麦克风 / 定位等权限请求的 App 内确认弹窗（默认不授权）。 */
private class V2PermissionPrompt(
    val title: String,
    val message: String,
    val onGrant: () -> Unit,
    val onDeny: () -> Unit,
)

private class V2DownloadInfo(
    val status: Int,
    val done: Long,
    val total: Long,
    val reason: Int,
)

/**
 * App 内嵌浏览器：对话里点开的网址、Codex / GitHub 这类登录授权页、以及「我的」里的
 * 独立浏览入口都在这里打开，不跳系统浏览器。
 *
 * 站点会话（[html] 为空）会持久化 Cookie 与 DOM Storage：离开页面或进程被杀后再回来，
 * 登录态还在。第三方 cookie 只对当前 WebView 打开，登录 / OAuth 弹窗才不会失败；
 * 退出页面、退到后台前都会 flush，避免进程被杀时丢掉未落盘的 cookie。
 *
 * 离线预览（[html] 不为空）走 [OFFLINE_PREVIEW_BROWSER_POLICY]：独立合成域名、
 * 不放第三方 cookie、不访问外部网络，和站点登录态互相隔离。
 *
 * 自签证书只对 A 机（159.75.222.60）与 B 机（115.159.125.254）放行，其他站点证书异常
 * 仍然拦截并提示——不因为内嵌浏览器降低整机安全边界。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun V2BrowserScreen(
    url: String,
    extraTop: Dp,
    showBack: Boolean = true,
    html: String? = null,
    title: String? = null,
    /** 从对话进入时带上任务 id，提取后可以「发回当前对话」。 */
    taskId: String? = null,
    /** 提取成功后打开产物详情；为空时只提示已保存。 */
    onOpenArtifact: ((String) -> Unit)? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val offlineHtml = html
    val policy = remember(offlineHtml) { browserPolicyFor(offlineHtml) }
    val settingsStore = remember { V2BrowserSettingsStore(context) }
    val clipboard = LocalClipboardManager.current
    // 空白入口（「我的」内置浏览器）恢复上次页面；明确链接仍然直接打开该链接
    val savedUrl = remember(url, offlineHtml) {
        if (policy.isOfflinePreview) {
            null
        } else {
            V2BrowserRetainedState.siteUrl ?: settingsStore.lastSiteUrl()
        }
    }
    val initialUrl = remember(url, offlineHtml) { browserEntryUrl(policy, url, savedUrl) }
    val initialAddress = remember(initialUrl) { browserAddressText(initialUrl) }
    // 只有「恢复上次页面」时才用 WebView saveState；打开新链接不套用旧历史
    val retainedWebState = remember(initialUrl, offlineHtml) {
        if (
            !policy.isOfflinePreview &&
            shouldRestoreBrowserSession(policy, url) &&
            V2BrowserRetainedState.siteUrl == initialUrl
        ) {
            V2BrowserRetainedState.siteState
        } else {
            null
        }
    }

    var address by remember(initialUrl) { mutableStateOf(initialAddress) }
    var addressDirty by remember(initialUrl) { mutableStateOf(false) }
    var progress by remember(initialUrl) { mutableStateOf(0f) }
    var canGoBack by remember(initialUrl) { mutableStateOf(false) }
    var canGoForward by remember(initialUrl) { mutableStateOf(false) }
    // 失败页是一套有分类、文案和动作的状态；不再只有一个 errorText 字符串
    var failure by remember(initialUrl) { mutableStateOf<V2BrowserFailure?>(null) }
    // OAuth / 登录弹窗：onCreateWindow 创建的 WebView 叠在主页面上，可手动关闭
    var popupView by remember(initialUrl) { mutableStateOf<WebView?>(null) }
    var popupOpen by remember(initialUrl) { mutableStateOf(false) }
    // 顶栏溢出菜单：复制 / 分享 / 提取到创作 / 系统浏览器
    var toolsOpen by remember(initialUrl) { mutableStateOf(false) }
    // 提取到创作：先选择类型（可顺带发回当前对话），再走加载 / 成功 / 失败弹层
    var extractUi by remember(initialUrl) { mutableStateOf<V2BrowserExtractUi?>(null) }
    var extractChoice by remember(initialUrl) { mutableStateOf<V2BrowserExtractType?>(null) }
    val scope = rememberCoroutineScope()

    // 站点会话的 UA 选择跨进入 / 退出保留，默认桌面版
    var desktopMode by remember(initialUrl) { mutableStateOf(settingsStore.desktopMode()) }
    // 页内查找
    var findOpen by remember(initialUrl) { mutableStateOf(false) }
    var findQuery by remember(initialUrl) { mutableStateOf("") }
    var findActive by remember(initialUrl) { mutableStateOf(0) }
    var findTotal by remember(initialUrl) { mutableStateOf(0) }
    // 最近访问（仅站点会话）
    val historyStore = remember { V2BrowserHistoryStore(context) }
    var history by remember { mutableStateOf(historyStore.load()) }
    var historyOpen by remember(initialUrl) { mutableStateOf(false) }
    // JS alert / confirm / prompt
    var jsDialog by remember(initialUrl) { mutableStateOf<V2JsDialogState?>(null) }
    var promptText by remember(initialUrl) { mutableStateOf("") }
    // 摄像头 / 麦克风 / 定位
    var permissionPrompt by remember(initialUrl) { mutableStateOf<V2PermissionPrompt?>(null) }
    // 文件选择
    var fileChooserCallback by remember(initialUrl) {
        mutableStateOf<ValueCallback<Array<Uri>>?>(null)
    }
    // 下载进度提示
    var downloadId by remember(initialUrl) { mutableStateOf<Long?>(null) }
    var downloadName by remember(initialUrl) { mutableStateOf("") }
    var downloadMessage by remember(initialUrl) { mutableStateOf<String?>(null) }
    var downloadProgress by remember(initialUrl) { mutableStateOf<Float?>(null) }

    val cookieManager = remember {
        CookieManager.getInstance().apply {
            // 全局开关只是允许 WebView 使用 cookie 存储；真正的读写范围由每个 WebView 决定
            setAcceptCookie(true)
        }
    }
    val downloadManager = remember { context.getSystemService(DownloadManager::class.java) }
    val frame = remember(initialUrl, offlineHtml) { FrameLayout(context) }

    // 系统文档选择器：只对站点会话开放；离线预览不读本地文件
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@rememberLauncherForActivityResult
        val data = result.data
        val uris = mutableListOf<Uri>()
        if (result.resultCode == Activity.RESULT_OK) {
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) uris += clip.getItemAt(index).uri
            }
            if (uris.isEmpty()) data?.data?.let { uris += it }
        }
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    fun addPopup(popup: WebView) {
        // 同一时间只保留最上层一个弹窗：新的 window.open 先关掉旧的，避免叠 WebView 泄漏
        popupView?.let { previous ->
            if (previous !== popup) {
                frame.removeView(previous)
                runCatching { previous.stopLoading() }
                runCatching { previous.destroy() }
            }
        }
        frame.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        popupView = popup
        popupOpen = true
    }

    fun removePopup(popup: WebView) {
        frame.removeView(popup)
        runCatching { popup.stopLoading() }
        runCatching { popup.destroy() }
        if (popupView === popup) popupView = null
        popupOpen = popupView != null
        // 登录弹窗关闭时就把新 cookie 落盘，不必等用户退出浏览器
        runCatching { cookieManager.flush() }
    }

    fun closePopup() {
        popupView?.let { removePopup(it) }
    }

    fun applyUserAgent(web: WebView) {
        browserUserAgent(policy, desktopMode)?.let { ua ->
            runCatching { web.settings.userAgentString = ua }
        }
    }

    /** 只记录真实网页；离线预览 / about:blank / file 不写历史。 */
    fun recordVisit(url: String?, title: String?) {
        if (!shouldRecordHistory(policy, url)) return
        val entry = BrowserHistoryEntry(
            url = url!!.trim(),
            title = title?.trim().orEmpty(),
            visitedAt = System.currentTimeMillis(),
        )
        // 进程随时可能被杀：最后页面也落盘，重进浏览器 / 重启后仍能回去
        settingsStore.saveLastSiteUrl(entry.url)
        val next = addBrowserHistory(history, entry)
        if (next === history) return
        history = next
        historyStore.save(next)
    }

    fun showJsDialog(dialog: V2JsDialogState) {
        jsDialog?.let { previous -> runCatching { previous.onCancel() } }
        jsDialog = dialog
        promptText = dialog.defaultValue
    }

    fun showPermissionPrompt(prompt: V2PermissionPrompt) {
        permissionPrompt?.let { previous -> runCatching { previous.onDeny() } }
        permissionPrompt = prompt
    }

    fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
        val mimeTypes = params?.acceptTypes
            ?.flatMap { it.split(',') }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1 && !mimeTypes[0].contains('*')) {
                mimeTypes[0]
            } else {
                "*/*"
            }
            if (mimeTypes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
            )
        }
    }

    /**
     * 站点会话的下载交给系统 DownloadManager：任务在系统进程里跑，退到后台不中断。
     * 只接 http(s)，并把当前 URL 的 cookie 与 UA 带到下载请求里（解析登录后文件的惯例）。
     */
    fun startDownload(
        url: String,
        requestUserAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        downloadId = null
        downloadName = ""
        downloadProgress = null
        downloadMessage = null
        if (!isDownloadUrlAllowed(policy, url)) {
            downloadMessage = "已拦截不安全的下载"
            return
        }
        val manager = downloadManager
        if (manager == null) {
            downloadMessage = "系统下载不可用"
            return
        }
        val name = downloadFileName(contentDisposition, url, mimeType)
        val host = httpUrlHost(url)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(name)
            setDescription(if (host != null) "来自 $host" else "来自网页")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mimeType?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { setMimeType(it) } }
            }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } else {
                    setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
                }
            }.onFailure {
                runCatching {
                    setDestinationInExternalFilesDir(
                        context,
                        Environment.DIRECTORY_DOWNLOADS,
                        name,
                    )
                }
            }
            runCatching { cookieManager.getCookie(url) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { cookie -> runCatching { addRequestHeader("Cookie", cookie) } }
            (requestUserAgent?.takeIf { it.isNotBlank() }
                ?: browserUserAgent(policy, desktopMode))
                ?.let { runCatching { addRequestHeader("User-Agent", it) } }
        }
        val id = runCatching { manager.enqueue(request) }.getOrNull()
        if (id == null) {
            downloadMessage = "无法开始下载"
            return
        }
        downloadId = id
        downloadName = name
        downloadMessage = "正在下载"
    }

    /**
     * Google 等站点会明确拒绝 WebView 登录（disallowed_useragent）。页面本身可能
     * 正常返回 200，所以只能在加载完成后读标题 / 正文做识别；命中后给出中文说明，
     * 并把「用系统浏览器打开」保留为出口，而不是把主浏览换成 Custom Tabs。
     */
    fun detectLoginRestriction(view: WebView?, finishedUrl: String?) {
        if (policy.isOfflinePreview) return
        val host = httpUrlHost(finishedUrl) ?: return
        if (host != "google.com" && !host.endsWith(".google.com")) return
        val script = "(function(){try{return (document.title||'')+'\\n'" +
            "+((document.body&&document.body.innerText)||'');}catch(e){return '';}})()"
        runCatching {
            view?.evaluateJavascript(script) { raw ->
                val text = decodeEvaluatedJavascriptString(raw).orEmpty()
                val restricted = browserLoginRestrictionFailure(finishedUrl, view.title, text)
                if (restricted != null) {
                    failure = preferredBrowserFailure(failure, restricted)
                }
            }
        }
    }

    /**
     * 主页面和登录弹窗共用同一套配置：同样的持久化 cookie、同一份证书白名单，
     * 弹窗只是在上面叠了一层可关闭的 WebView。
     */
    fun createWebView(isPopup: Boolean): WebView {
        val web = WebView(context)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            // 站点会话允许 window.open / OAuth 新窗口；离线预览保持关闭
            setSupportMultipleWindows(policy.supportsMultipleWindows)
            javaScriptCanOpenWindowsAutomatically = policy.supportsMultipleWindows
        }
        // 站点会话默认桌面 Chrome UA；离线预览保留系统默认 UA
        applyUserAgent(web)
        runCatching {
            cookieManager.setAcceptThirdPartyCookies(web, policy.acceptThirdPartyCookies)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url ?: return false
                val scheme = target.scheme.orEmpty().lowercase()
                // 离线预览不加载外部网络资源，也不把预览页的 cookie 带到真实站点
                if (shouldBlockPreviewRequest(policy, scheme, target.host)) {
                    if (!isPopup && request?.isForMainFrame == true) {
                        failure = browserPreviewBlockedFailure()
                    }
                    return true
                }
                // http/https 一律留在内置浏览器里，不跳系统浏览器
                if (isWebViewInternalScheme(scheme)) return false
                if (scheme == "flux" && target.host == "github-link") {
                    // GitHub 授权回跳直接交给仓库登录总线，不离开内置浏览器。
                    GitHubLinkBus.publish(
                        code = target.getQueryParameter("code").orEmpty(),
                        state = target.getQueryParameter("state").orEmpty(),
                    )
                    return true
                }
                // 非网页协议（tel / mailto / 第三方 App scheme）交回系统处理
                if (!shouldHandOffToSystem(policy, scheme)) return true
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, target))
                }
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val target = request?.url ?: return null
                if (!shouldBlockPreviewRequest(policy, target.scheme, target.host)) return null
                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    ByteArrayInputStream(ByteArray(0)),
                )
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                val failedUrl = error.url
                val host = runCatching { Uri.parse(failedUrl).host.orEmpty() }
                    .getOrDefault("")
                if (isTrustedSslHost(host)) {
                    handler.proceed()
                } else {
                    handler.cancel()
                    val sslFailure = browserSslFailure(host, failedUrl)
                    if (isPopup) {
                        // 登录弹窗叠在主页面上，错误层会挡住主页面；用系统提示说明拦截
                        Toast.makeText(
                            context,
                            "证书不受信任：${host.ifBlank { "该网站" }}，已阻止登录窗口加载",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        failure = preferredBrowserFailure(failure, sslFailure)
                    }
                }
            }

            override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                if (isPopup) return
                // 新导航开始才清旧错误；progress 完成回调不再碰失败态
                failure = null
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (!isPopup && request?.isForMainFrame == true) {
                    val failedUrl = request.url?.toString()
                    val networkUnavailable = isBrowserNetworkErrorCode(error?.errorCode ?: 0)
                    failure = preferredBrowserFailure(
                        failure,
                        browserLoadFailure(
                            failedUrl = failedUrl,
                            networkUnavailable = networkUnavailable,
                            description = error?.description?.toString(),
                        ),
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (isPopup || request?.isForMainFrame != true) return
                val statusCode = errorResponse?.statusCode ?: return
                if (statusCode < 400) return
                failure = preferredBrowserFailure(
                    failure,
                    browserHttpFailure(request.url?.toString(), statusCode),
                )
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                if (isPopup) return
                canGoBack = view?.canGoBack() == true
                canGoForward = view?.canGoForward() == true
                recordVisit(finishedUrl ?: view?.url, view?.title)
                detectLoginRestriction(view, finishedUrl ?: view?.url)
            }

            override fun doUpdateVisitedHistory(
                view: WebView?,
                url: String?,
                isReload: Boolean,
            ) {
                if (isPopup) return
                canGoBack = view?.canGoBack() == true
                canGoForward = view?.canGoForward() == true
                // 空白会话保持地址栏为空，不要把 about:blank 填进去
                if (!addressDirty && !url.isNullOrBlank() && url != "about:blank") {
                    address = url
                }
                // 单页应用 pushState 不一定触发 onPageFinished，这里也记一次最后页面
                settingsStore.saveLastSiteUrl(url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (isPopup) return
                // 只推进度条；100% 不能清掉 SSL / HTTP / 加载失败
                val next = applyBrowserProgress(
                    V2BrowserProgressState(progress = progress, failure = failure),
                    newProgress,
                )
                progress = next.progress
                failure = next.failure
                if (newProgress >= 100) {
                    canGoBack = view?.canGoBack() == true
                    canGoForward = view?.canGoForward() == true
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                if (!policy.supportsMultipleWindows) return false
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = createWebView(isPopup = true)
                addPopup(popup)
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                if (window != null && window !== web) removePopup(window)
            }

            /**
             * 摄像头 / 麦克风 / 受保护媒体：先问用户，永远不静默 grant。
             * 拒绝或返回键关闭都显式 deny，避免页面一直等权限。
             */
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val resources = request.resources ?: emptyArray()
                if (policy.isOfflinePreview || resources.isEmpty()) {
                    runCatching { request.deny() }
                    return
                }
                val origin = runCatching { request.origin?.host.orEmpty() }
                    .getOrDefault("")
                    .ifBlank { "该网页" }
                showPermissionPrompt(
                    V2PermissionPrompt(
                        title = "网页请求权限",
                        message = "$origin 请求使用${webPermissionLabel(resources)}。"
                            + "只有你点「允许」才会授权。",
                        onGrant = { runCatching { request.grant(resources) } },
                        onDeny = { runCatching { request.deny() } },
                    ),
                )
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                if (callback == null) return
                if (policy.isOfflinePreview) {
                    callback.invoke(origin, false, false)
                    return
                }
                val host = httpUrlHost(origin) ?: "该网页"
                showPermissionPrompt(
                    V2PermissionPrompt(
                        title = "网页请求定位",
                        message = "$host 请求获取你的位置。只有你点「允许」才会授权。",
                        onGrant = { callback.invoke(origin, true, false) },
                        onDeny = { callback.invoke(origin, false, false) },
                    ),
                )
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                showJsDialog(
                    V2JsDialogState(
                        kind = V2JsDialogKind.ALERT,
                        message = message.orEmpty(),
                        defaultValue = "",
                        onConfirm = { runCatching { result?.confirm() } },
                        onCancel = { runCatching { result?.cancel() } },
                    ),
                )
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                showJsDialog(
                    V2JsDialogState(
                        kind = V2JsDialogKind.CONFIRM,
                        message = message.orEmpty(),
                        defaultValue = "",
                        onConfirm = { runCatching { result?.confirm() } },
                        onCancel = { runCatching { result?.cancel() } },
                    ),
                )
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                showJsDialog(
                    V2JsDialogState(
                        kind = V2JsDialogKind.PROMPT,
                        message = message.orEmpty(),
                        defaultValue = defaultValue.orEmpty(),
                        onConfirm = { text -> runCatching { result?.confirm(text.orEmpty()) } },
                        onCancel = { runCatching { result?.cancel() } },
                    ),
                )
                return true
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                // 离线预览不开放本地文件选择
                if (policy.isOfflinePreview) {
                    runCatching { filePathCallback?.onReceiveValue(null) }
                    return false
                }
                // 同时只允许一个未完成的文件选择
                fileChooserCallback?.let { runCatching { it.onReceiveValue(null) } }
                fileChooserCallback = filePathCallback
                val launched = runCatching {
                    fileChooserLauncher.launch(buildFileChooserIntent(fileChooserParams))
                }
                if (launched.isFailure) {
                    fileChooserCallback = null
                    runCatching { filePathCallback?.onReceiveValue(null) }
                    return false
                }
                return true
            }
        }
        web.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
            startDownload(downloadUrl, userAgent, contentDisposition, mimeType)
        }
        // 页内查找结果回传给紧凑搜索条（只有主页面需要）
        if (!isPopup) {
            web.setFindListener(
                WebView.FindListener { activeMatchOrdinal, totalMatches, _ ->
                    findTotal = totalMatches.coerceAtLeast(0)
                    findActive = if (activeMatchOrdinal >= 0) activeMatchOrdinal + 1 else 0
                },
            )
        }
        return web
    }

    val webView = remember(initialUrl, offlineHtml) {
        createWebView(isPopup = false).also { frame.addView(it) }
    }

    // 离开页面：先把页面状态和 cookie 收好，再销毁 WebView
    DisposableEffect(webView) {
        onDispose {
            // 未完成的文件选择 / JS 对话框 / 权限请求都要显式收尾，避免 WebView 或页面卡住
            fileChooserCallback?.let { runCatching { it.onReceiveValue(null) } }
            fileChooserCallback = null
            jsDialog?.let { runCatching { it.onCancel() } }
            jsDialog = null
            permissionPrompt?.let { runCatching { it.onDeny() } }
            permissionPrompt = null
            runCatching { cookieManager.flush() }
            if (!policy.isOfflinePreview) {
                settingsStore.saveDesktopMode(desktopMode)
                val lastUrl = webView.url
                // 只有真实网页才值得恢复；空会话不覆盖上一次的页面
                if (shouldRecordHistory(policy, lastUrl)) {
                    settingsStore.saveLastSiteUrl(lastUrl)
                    runCatching {
                        val state = Bundle()
                        webView.saveState(state)
                        V2BrowserRetainedState.remember(lastUrl, state)
                    }
                }
            }
            popupView?.let { popup ->
                frame.removeView(popup)
                runCatching { popup.stopLoading() }
                runCatching { popup.destroy() }
            }
            popupView = null
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    // 退到后台（进程随时可能被回收）也先把 cookie 落盘
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView.onPause()
                    popupView?.onPause()
                    runCatching { cookieManager.flush() }
                }
                Lifecycle.Event.ON_STOP -> runCatching { cookieManager.flush() }
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    popupView?.onResume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 下载进度：轮询系统 DownloadManager，完成 / 失败后短暂提示再收起
    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        while (true) {
            val info = queryDownload(downloadManager, id)
            if (info == null) {
                downloadProgress = null
                downloadMessage = "下载任务丢失"
                delay(2_500)
                break
            }
            when (info.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    downloadProgress = 1f
                    downloadMessage = "下载完成"
                    delay(3_000)
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    downloadProgress = null
                    val reason = downloadFailureReason(info.reason)
                    downloadMessage = if (reason != null) "下载失败：$reason" else "下载失败"
                    delay(4_000)
                    break
                }
                else -> {
                    downloadProgress = if (info.total > 0) {
                        (info.done.toFloat() / info.total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    downloadMessage = "正在下载"
                }
            }
            delay(600)
        }
        if (downloadId == id) {
            downloadId = null
            downloadName = ""
            downloadMessage = null
            downloadProgress = null
        }
    }

    fun loadHtml() {
        val source = offlineHtml ?: return
        failure = null
        progress = 0f
        webView.loadDataWithBaseURL(policy.baseUrl, source, "text/html", "utf-8", null)
    }

    fun reloadPage() {
        failure = null
        if (offlineHtml != null) loadHtml() else webView.reload()
    }

    /** 地址栏 / 最近访问 / 失败页重试共用的导航入口；空输入不导航。 */
    fun load(target: String) {
        val normalized = browserNavigationTarget(target) ?: return
        address = if (normalized == "about:blank") "" else normalized
        addressDirty = false
        failure = null
        downloadMessage = null
        webView.loadUrl(normalized)
    }

    /** 失败页重试：回到当前页 / 进入地址，而不是拿空的地址栏把页面清成 about:blank。 */
    fun retryAfterFailure() {
        failure = null
        if (offlineHtml != null) {
            loadHtml()
            return
        }
        val target = browserRetryUrl(policy, webView.url, initialUrl, address)
        if (target != null) webView.loadUrl(target) else webView.reload()
    }

    /** 当前可复制 / 分享 / 外开的链接；空白页与离线预览返回 null。 */
    fun currentLink(): String? = browserShareableUrl(policy, webView.url, address)

    fun copyCurrentLink() {
        val link = currentLink() ?: return
        clipboard.setText(AnnotatedString(link))
        Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
    }

    fun shareCurrentLink() {
        val link = currentLink() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
            webView.title?.takeIf { it.isNotBlank() }
                ?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        val launched = runCatching {
            context.startActivity(Intent.createChooser(send, "分享链接"))
        }
        if (launched.isFailure) {
            Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
        }
    }

    fun openInSystemBrowser(url: String?) {
        val target = browserShareableUrl(policy, url, null) ?: return
        val launched = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }
        if (launched.isFailure) {
            Toast.makeText(context, "没有可用的系统浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 读取当前页 outerHTML 一并交给服务端，避开部分站点对服务端抓取的拦截。
     * 拿不到（页面还没加载完 / 结果过大 / WebView 已销毁）就返回 null，由服务端按 URL 抓取。
     */
    suspend fun capturePageHtml(): String? = withTimeoutOrNull(2_500L) {
        suspendCancellableCoroutine { continuation ->
            val script = "(function(){try{return document.documentElement.outerHTML;}" +
                "catch(e){return null;}})()"
            val started = runCatching {
                webView.evaluateJavascript(script) { raw ->
                    if (continuation.isActive) continuation.resume(browserExtractHtml(raw))
                }
            }
            if (started.isFailure && continuation.isActive) continuation.resume(null)
        }
    }

    /** 提取到创作：服务端完成抓取与正文抽取；失败保留可重试状态，不静默丢操作。 */
    fun startExtraction(type: V2BrowserExtractType, sendToChat: Boolean) {
        val link = currentLink()
        if (link == null) {
            extractChoice = null
            Toast.makeText(context, EXTRACT_NO_LINK_HINT, Toast.LENGTH_SHORT).show()
            return
        }
        extractChoice = null
        extractUi = V2BrowserExtractUi(type, sendToChat, loading = true)
        scope.launch {
            try {
                val html = if (browserCanExtract(policy)) capturePageHtml() else null
                val api = FluxEnv.api(FluxEnv.configWithFreshToken())
                val result = api.extractArtifact(
                    url = link,
                    type = type.apiType,
                    html = html,
                    title = webView.title,
                    taskId = if (sendToChat) taskId else null,
                )
                extractUi = V2BrowserExtractUi(
                    type = type,
                    sendToChat = sendToChat,
                    loading = false,
                    artifactId = result.artifact.id,
                    fallback = result.fallback,
                    sentToChat = result.taskId != null,
                    note = result.note,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                extractUi = V2BrowserExtractUi(
                    type = type,
                    sendToChat = sendToChat,
                    loading = false,
                    error = browserExtractErrorText(e),
                )
            }
        }
    }

    /** 菜单点击：从对话进入且拿得到任务 id 时，先问一句是否顺带发回当前对话。 */
    fun chooseExtraction(type: V2BrowserExtractType) {
        toolsOpen = false
        if (browserCanExtract(policy) && browserCanSendExtractToChat(policy, taskId)) {
            extractChoice = type
        } else {
            startExtraction(type, sendToChat = false)
        }
    }

    fun runFind(query: String) {
        if (query.isBlank()) {
            runCatching { webView.clearMatches() }
            findActive = 0
            findTotal = 0
        } else {
            runCatching { webView.findAllAsync(query) }
        }
    }

    fun closeFind() {
        findOpen = false
        findQuery = ""
        findActive = 0
        findTotal = 0
        runCatching { webView.clearMatches() }
    }

    fun toggleDesktopMode() {
        if (policy.isOfflinePreview) return
        desktopMode = !desktopMode
        settingsStore.saveDesktopMode(desktopMode)
        applyUserAgent(webView)
        popupView?.let { applyUserAgent(it) }
        reloadPage()
    }

    // 系统返回：先关弹层，再让 WebView 后退，最后才退出浏览器页
    BackHandler {
        when (
            decideBrowserBack(
                jsDialogOpen = jsDialog != null,
                permissionOpen = permissionPrompt != null,
                popupOpen = popupOpen,
                findOpen = findOpen,
                historyOpen = historyOpen,
                canGoBack = webView.canGoBack(),
            )
        ) {
            V2BrowserBackAction.CLOSE_JS_DIALOG -> {
                val dialog = jsDialog
                jsDialog = null
                runCatching { dialog?.onCancel() }
            }
            V2BrowserBackAction.CLOSE_PERMISSION -> {
                val prompt = permissionPrompt
                permissionPrompt = null
                runCatching { prompt?.onDeny() }
            }
            V2BrowserBackAction.CLOSE_POPUP -> closePopup()
            V2BrowserBackAction.CLOSE_FIND -> closeFind()
            V2BrowserBackAction.CLOSE_HISTORY -> historyOpen = false
            V2BrowserBackAction.WEBVIEW_BACK -> webView.goBack()
            V2BrowserBackAction.EXIT -> onBack()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(V2Colors.shellTop),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp + extraTop, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                RoundIconButton(
                    Icons.Outlined.ArrowBack,
                    onBack,
                    size = 32.dp,
                    iconSize = 16.dp,
                    contentDescription = "返回",
                )
                Spacer(Modifier.width(10.dp))
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(V2Colors.surface(.60f))
                    .border(1.dp, V2Colors.line, RoundedCornerShape(11.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Language,
                    contentDescription = null,
                    tint = V2Colors.ink(.32f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(7.dp))
                if (offlineHtml != null) {
                    Label(
                        title ?: "网页预览",
                        11.5f,
                        V2Colors.ink(.82f),
                        weight = FontWeight.Medium,
                        mono = false,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    Label("本地渲染", 9f, V2Colors.ink(.34f), mono = true)
                } else {
                    BasicTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            addressDirty = true
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 11.5.sp,
                            color = V2Colors.ink(.82f),
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(V2Colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { load(address) }),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (address.isEmpty()) {
                                    Label(
                                        "输入网址",
                                        11.5f,
                                        V2Colors.ink(.30f),
                                        weight = FontWeight.Medium,
                                        mono = false,
                                        maxLines = 1,
                                    )
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (address.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier
                                .size(20.dp)
                                .clickable {
                                    address = ""
                                    addressDirty = true
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "清空地址",
                                tint = V2Colors.ink(.34f),
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    if (address.isNotBlank() && addressDirty) {
                        Spacer(Modifier.width(4.dp))
                        Label(
                            "前往",
                            10f,
                            V2Colors.ink(.82f),
                            weight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { load(address) }
                                .padding(horizontal = 5.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundIconButton(
                Icons.Outlined.Refresh,
                onClick = { reloadPage() },
                size = 32.dp,
                iconSize = 15.dp,
                contentDescription = "刷新",
            )
            if (!policy.isOfflinePreview) {
                Spacer(Modifier.width(6.dp))
                RoundIconButton(
                    Icons.Outlined.History,
                    onClick = { historyOpen = true },
                    size = 32.dp,
                    iconSize = 15.dp,
                    contentDescription = "最近访问",
                )
                Spacer(Modifier.width(6.dp))
                // 页面工具放在溢出菜单里，不新增底栏 Tab
                Box {
                    RoundIconButton(
                        Icons.Outlined.MoreVert,
                        onClick = { toolsOpen = true },
                        size = 32.dp,
                        iconSize = 15.dp,
                        contentDescription = "页面工具",
                    )
                    DropdownMenu(
                        expanded = toolsOpen,
                        onDismissRequest = { toolsOpen = false },
                    ) {
                        val link = currentLink()
                        DropdownMenuItem(
                            text = { Label("复制链接", 12f, V2Colors.ink(.82f)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                    tint = V2Colors.ink(.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            enabled = link != null,
                            onClick = {
                                toolsOpen = false
                                copyCurrentLink()
                            },
                        )
                        DropdownMenuItem(
                            text = { Label("分享链接", 12f, V2Colors.ink(.82f)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Share,
                                    contentDescription = null,
                                    tint = V2Colors.ink(.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            enabled = link != null,
                            onClick = {
                                toolsOpen = false
                                shareCurrentLink()
                            },
                        )
                        DropdownMenuItem(
                            text = { Label(EXTRACT_DOC_LABEL, 12f, V2Colors.ink(.82f)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = V2Colors.ink(.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            enabled = link != null,
                            onClick = { chooseExtraction(V2BrowserExtractType.DOC) },
                        )
                        DropdownMenuItem(
                            text = { Label(EXTRACT_PAGE_LABEL, 12f, V2Colors.ink(.82f)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Language,
                                    contentDescription = null,
                                    tint = V2Colors.ink(.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            enabled = link != null,
                            onClick = { chooseExtraction(V2BrowserExtractType.PAGE) },
                        )
                        DropdownMenuItem(
                            text = { Label("在系统浏览器打开", 12f, V2Colors.ink(.82f)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.OpenInNew,
                                    contentDescription = null,
                                    tint = V2Colors.ink(.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            enabled = link != null,
                            onClick = {
                                toolsOpen = false
                                openInSystemBrowser(link)
                            },
                        )
                    }
                }
            }
        }

        // 页内查找：紧凑搜索条，不新开页面
        if (findOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(V2Colors.surface(.60f))
                        .border(1.dp, V2Colors.line, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = V2Colors.ink(.32f),
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = findQuery,
                        onValueChange = {
                            findQuery = it
                            runFind(it)
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 11.sp,
                            color = V2Colors.ink(.82f),
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(V2Colors.accent),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (findQuery.isEmpty()) {
                                    Label(
                                        "在页面中查找",
                                        11f,
                                        V2Colors.ink(.30f),
                                        weight = FontWeight.Medium,
                                    )
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    browserFindStatus(findQuery, findActive, findTotal)?.let { status ->
                        Label(
                            status,
                            9f,
                            V2Colors.ink(.42f),
                            mono = true,
                        )
                    }
                }
                // 没有匹配时前后跳转置灰，避免用户反复点一个不会动的按钮
                val findNavigable = findQuery.isNotBlank() && findTotal > 0
                RoundIconButton(
                    Icons.Outlined.KeyboardArrowUp,
                    onClick = { if (findNavigable) webView.findNext(false) },
                    size = 30.dp,
                    iconSize = 14.dp,
                    tint = if (findNavigable) V2Colors.ink(.62f) else V2Colors.ink(.22f),
                    contentDescription = "上一个",
                )
                RoundIconButton(
                    Icons.Outlined.KeyboardArrowDown,
                    onClick = { if (findNavigable) webView.findNext(true) },
                    size = 30.dp,
                    iconSize = 14.dp,
                    tint = if (findNavigable) V2Colors.ink(.62f) else V2Colors.ink(.22f),
                    contentDescription = "下一个",
                )
                RoundIconButton(
                    Icons.Outlined.Close,
                    onClick = { closeFind() },
                    size = 30.dp,
                    iconSize = 14.dp,
                    contentDescription = "关闭查找",
                )
            }
        }

        // 加载进度：贴顶的一条细线，不遮挡页面内容
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(V2Colors.lineSoft),
        ) {
            if (progress < 1f) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(V2Colors.accent),
                )
            }
        }

        // 下载进度 / 完成提示：细条 + 一行文字，不占底部 Tab
        if (downloadMessage != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(V2Colors.surface(.45f))
                    .padding(horizontal = 16.dp, vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(
                        downloadMessage.orEmpty(),
                        9.5f,
                        V2Colors.ink(.62f),
                        weight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    downloadProgress?.let { ratio ->
                        Label(
                            "${(ratio.coerceIn(0f, 1f) * 100).toInt()}%",
                            9f,
                            V2Colors.ink(.45f),
                            mono = true,
                        )
                    }
                }
                if (downloadName.isNotBlank()) {
                    Label(
                        downloadName,
                        8.5f,
                        V2Colors.ink(.34f),
                        mono = true,
                        maxLines = 1,
                    )
                }
                downloadProgress?.let { ratio ->
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(V2Colors.lineSoft),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                                .height(1.dp)
                                .background(V2Colors.accent),
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = {
                    frame.also {
                        if (offlineHtml != null) {
                            loadHtml()
                        } else {
                            // 从空白入口回来：先尝试恢复上次的 WebView 历史；失败再按 URL 加载
                            val restored = retainedWebState?.let { state ->
                                runCatching { webView.restoreState(state) }.getOrNull()
                            }
                            if (restored == null) webView.loadUrl(initialUrl)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // 登录 / OAuth 弹窗没有自己关掉时，给一个显式的关闭入口
            if (popupOpen) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(V2Colors.accent.copy(alpha = .92f))
                        .clickable { closePopup() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label("关闭弹窗", 10f, V2Colors.actionInk, FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        tint = V2Colors.actionInk,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            // 失败页：分类标题 + 说明 + 可执行动作，而不是只改一行错误文字
            failure?.let { problem ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(V2Colors.shellTop.copy(alpha = .94f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Label(
                            if (
                                policy.isOfflinePreview &&
                                problem.kind == V2BrowserFailureKind.LOAD
                            ) {
                                "预览打不开"
                            } else {
                                problem.title
                            },
                            14f,
                            V2Colors.ink(.86f),
                            FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Label(
                            problem.message,
                            10f,
                            V2Colors.ink(.48f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        problem.detail?.let { detail ->
                            Spacer(Modifier.height(6.dp))
                            Label(
                                detail,
                                9f,
                                V2Colors.ink(.34f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                maxLines = 3,
                            )
                        }
                        problem.failedUrl
                            ?.takeIf { !policy.isOfflinePreview && httpUrlHost(it) != null }
                            ?.let { url ->
                                Spacer(Modifier.height(6.dp))
                                Label(
                                    url,
                                    8.5f,
                                    V2Colors.ink(.30f),
                                    mono = true,
                                    maxLines = 2,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        Spacer(Modifier.height(14.dp))
                        if (problem.canRetry) {
                            GhostButton(
                                text = "重试",
                                onClick = { retryAfterFailure() },
                                modifier = Modifier.fillMaxWidth(),
                                icon = Icons.Outlined.Refresh,
                            )
                        }
                        if (problem.canOpenExternally) {
                            Spacer(Modifier.height(8.dp))
                            GhostButton(
                                text = "在系统浏览器打开",
                                onClick = { openInSystemBrowser(problem.failedUrl) },
                                modifier = Modifier.fillMaxWidth(),
                                icon = Icons.Outlined.OpenInNew,
                            )
                        }
                        if (problem.canShowPageAnyway) {
                            Spacer(Modifier.height(8.dp))
                            GhostButton(
                                text = "继续查看网页",
                                onClick = { failure = null },
                                modifier = Modifier.fillMaxWidth(),
                                icon = Icons.Outlined.Language,
                            )
                        }
                    }
                }
            }
            // 最近访问列表：简单列表，点一下即打开，不是完整书签系统
            if (historyOpen) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(V2Colors.shellTop),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Label("最近访问", 13f, V2Colors.ink(.86f), FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Label(
                            "${history.size}/$BROWSER_HISTORY_LIMIT",
                            9f,
                            V2Colors.ink(.30f),
                            mono = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        RoundIconButton(
                            Icons.Outlined.Close,
                            onClick = { historyOpen = false },
                            size = 28.dp,
                            iconSize = 13.dp,
                            contentDescription = "关闭最近访问",
                        )
                    }
                    ThinDivider()
                    if (history.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Label("还没有浏览记录", 11f, V2Colors.ink(.4f))
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(history, key = { it.url }) { entry ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            historyOpen = false
                                            load(entry.url)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                ) {
                                    Label(
                                        browserHistoryTitle(entry),
                                        12f,
                                        V2Colors.ink(.82f),
                                        FontWeight.Medium,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Label(
                                        entry.url,
                                        9f,
                                        V2Colors.ink(.38f),
                                        mono = true,
                                        maxLines = 1,
                                    )
                                }
                                ThinDivider(V2Colors.lineSoft)
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(
                Icons.Outlined.ArrowBack,
                onClick = { if (webView.canGoBack()) webView.goBack() },
                size = 36.dp,
                iconSize = 16.dp,
                tint = if (canGoBack) V2Colors.ink(.62f) else V2Colors.ink(.20f),
                contentDescription = "后退",
            )
            RoundIconButton(
                Icons.Outlined.ArrowForward,
                onClick = { if (webView.canGoForward()) webView.goForward() },
                size = 36.dp,
                iconSize = 16.dp,
                tint = if (canGoForward) V2Colors.ink(.62f) else V2Colors.ink(.20f),
                contentDescription = "前进",
            )
            RoundIconButton(
                Icons.Outlined.Refresh,
                onClick = { reloadPage() },
                size = 36.dp,
                iconSize = 16.dp,
                contentDescription = "刷新",
            )
            Spacer(Modifier.weight(1f))
            RoundIconButton(
                Icons.Outlined.Search,
                onClick = {
                    findOpen = true
                    if (findQuery.isNotBlank()) runFind(findQuery)
                },
                size = 36.dp,
                iconSize = 16.dp,
                tint = if (findOpen) V2Colors.ink(.86f) else V2Colors.ink(.55f),
                contentDescription = "页面内查找",
            )
            if (!policy.isOfflinePreview) {
                RoundIconButton(
                    if (desktopMode) Icons.Outlined.Computer else Icons.Outlined.Smartphone,
                    onClick = { toggleDesktopMode() },
                    size = 36.dp,
                    iconSize = 16.dp,
                    tint = if (desktopMode) V2Colors.ink(.86f) else V2Colors.ink(.55f),
                    contentDescription = if (desktopMode) "切换为移动版" else "切换为桌面版",
                )
            }
            Spacer(Modifier.weight(1f))
            RoundIconButton(
                Icons.Outlined.Close,
                onClick = onBack,
                size = 36.dp,
                iconSize = 16.dp,
                contentDescription = "退出浏览器",
            )
        }
    }

    // JS alert / confirm / prompt：用现有黑白弹窗应答，页面不会卡在空白 prompt
    jsDialog?.let { dialog ->
        val dismiss: () -> Unit = {
            val current = jsDialog
            jsDialog = null
            runCatching { current?.onCancel() }
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = {
                Label(
                    when (dialog.kind) {
                        V2JsDialogKind.ALERT -> "网页提示"
                        V2JsDialogKind.CONFIRM -> "网页询问"
                        V2JsDialogKind.PROMPT -> "网页输入"
                    },
                    16f,
                    V2Colors.ink(.92f),
                    FontWeight.SemiBold,
                )
            },
            text = {
                Column {
                    Label(dialog.message, 12.5f, V2Colors.ink(.58f))
                    if (dialog.kind == V2JsDialogKind.PROMPT) {
                        Spacer(Modifier.height(12.dp))
                        BasicTextField(
                            value = promptText,
                            onValueChange = { promptText = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 12.5.sp,
                                color = V2Colors.ink(.88f),
                            ),
                            cursorBrush = SolidColor(V2Colors.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(V2Colors.surface(.7f))
                                .border(1.dp, V2Colors.line, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val current = jsDialog
                        jsDialog = null
                        val text = promptText
                        runCatching {
                            if (current != null && current.kind == V2JsDialogKind.PROMPT) {
                                current.onConfirm(text)
                            } else {
                                current?.onConfirm(null)
                            }
                        }
                    },
                ) {
                    Label("确定", 13f, V2Colors.ink(.9f))
                }
            },
            dismissButton = if (dialog.kind == V2JsDialogKind.ALERT) {
                null
            } else {
                {
                    TextButton(onClick = dismiss) {
                        Label("取消", 13f, V2Colors.ink(.62f))
                    }
                }
            },
        )
    }

    // 摄像头 / 麦克风 / 定位：默认不授权，用户点「允许」才 grant
    permissionPrompt?.let { prompt ->
        val deny: () -> Unit = {
            val current = permissionPrompt
            permissionPrompt = null
            runCatching { current?.onDeny() }
        }
        AlertDialog(
            onDismissRequest = deny,
            title = { Label(prompt.title, 16f, V2Colors.ink(.92f), FontWeight.SemiBold) },
            text = { Label(prompt.message, 12.5f, V2Colors.ink(.58f)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val current = permissionPrompt
                        permissionPrompt = null
                        runCatching { current?.onGrant() }
                    },
                ) {
                    Label("允许", 13f, V2Colors.ink(.9f))
                }
            },
            dismissButton = {
                TextButton(onClick = deny) {
                    Label("拒绝", 13f, V2Colors.ink(.62f))
                }
            },
        )
    }

    // 从对话进入的浏览器：提取前先问是否顺带发回当前对话。
    extractChoice?.let { type ->
        AlertDialog(
            onDismissRequest = { extractChoice = null },
            title = {
                Label(browserExtractTitle(type), 16f, V2Colors.ink(.92f), FontWeight.SemiBold)
            },
            text = {
                Label(
                    "提取后会保存到「创作」。是否同时发回当前对话？",
                    12.5f,
                    V2Colors.ink(.58f),
                )
            },
            confirmButton = {
                TextButton(onClick = { startExtraction(type, sendToChat = true) }) {
                    Label("发回当前对话", 13f, V2Colors.ink(.9f))
                }
            },
            dismissButton = {
                TextButton(onClick = { startExtraction(type, sendToChat = false) }) {
                    Label("仅保存到创作", 13f, V2Colors.ink(.62f))
                }
            },
        )
    }

    // 提取中 / 失败重试 / 成功后的去向：加载和失败都有中文说明，不静默结束。
    extractUi?.let { ui ->
        when {
            ui.loading -> AlertDialog(
                onDismissRequest = {},
                title = { Label("正在提取", 16f, V2Colors.ink(.92f), FontWeight.SemiBold) },
                text = {
                    Label(
                        "正在读取页面正文并保存到「创作」，请稍候…",
                        12.5f,
                        V2Colors.ink(.58f),
                    )
                },
                confirmButton = {},
            )

            ui.error != null -> AlertDialog(
                onDismissRequest = { extractUi = null },
                title = { Label("提取失败", 16f, V2Colors.ink(.92f), FontWeight.SemiBold) },
                text = { Label(ui.error, 12.5f, V2Colors.ink(.58f)) },
                confirmButton = {
                    TextButton(onClick = { startExtraction(ui.type, ui.sendToChat) }) {
                        Label("重试", 13f, V2Colors.ink(.9f))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { extractUi = null }) {
                        Label("取消", 13f, V2Colors.ink(.62f))
                    }
                },
            )

            else -> {
                val artifactId = ui.artifactId
                AlertDialog(
                    onDismissRequest = { extractUi = null },
                    title = {
                        Label("已保存到创作", 16f, V2Colors.ink(.92f), FontWeight.SemiBold)
                    },
                    text = {
                        Column {
                            Label(
                                browserExtractSuccessText(ui.fallback, ui.sentToChat),
                                12.5f,
                                V2Colors.ink(.58f),
                            )
                            ui.note?.takeIf { it.isNotBlank() }?.let { note ->
                                Spacer(Modifier.height(6.dp))
                                Label(note, 10.5f, V2Colors.ink(.42f))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                extractUi = null
                                if (artifactId != null) onOpenArtifact?.invoke(artifactId)
                            },
                        ) {
                            Label(
                                if (onOpenArtifact != null) "查看产物" else "完成",
                                13f,
                                V2Colors.ink(.9f),
                            )
                        }
                    },
                    dismissButton = if (ui.sentToChat) {
                        {
                            TextButton(
                                onClick = {
                                    extractUi = null
                                    onBack()
                                },
                            ) {
                                Label("回到对话", 13f, V2Colors.ink(.62f))
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 查询 DownloadManager 任务；任务被清理或查询失败时返回 null。 */
private fun queryDownload(manager: DownloadManager?, id: Long): V2DownloadInfo? {
    if (manager == null) return null
    val cursor = runCatching {
        manager.query(DownloadManager.Query().setFilterById(id))
    }.getOrNull() ?: return null
    return cursor.use {
        if (!it.moveToFirst()) return@use null
        V2DownloadInfo(
            status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            done = it.getLong(
                it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            ),
            total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
        )
    }
}
