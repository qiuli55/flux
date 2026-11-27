package top.qiuli55.flux.mobile.ui.v2

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val offlineHtml = html
    val policy = remember(offlineHtml) { browserPolicyFor(offlineHtml) }
    val initialUrl = remember(url) { browserStartUrl(url) }
    val initialAddress = remember(url) { browserAddressText(url) }

    var address by remember(initialUrl) { mutableStateOf(initialAddress) }
    var addressDirty by remember(initialUrl) { mutableStateOf(false) }
    var progress by remember(initialUrl) { mutableStateOf(0f) }
    var canGoBack by remember(initialUrl) { mutableStateOf(false) }
    var canGoForward by remember(initialUrl) { mutableStateOf(false) }
    var errorText by remember(initialUrl) { mutableStateOf<String?>(null) }
    // OAuth / 登录弹窗：onCreateWindow 创建的 WebView 叠在主页面上，可手动关闭
    var popupView by remember(initialUrl) { mutableStateOf<WebView?>(null) }
    var popupOpen by remember(initialUrl) { mutableStateOf(false) }

    val cookieManager = remember {
        CookieManager.getInstance().apply {
            // 全局开关只是允许 WebView 使用 cookie 存储；真正的读写范围由每个 WebView 决定
            setAcceptCookie(true)
        }
    }
    val frame = remember(initialUrl, offlineHtml) { FrameLayout(context) }

    fun addPopup(popup: WebView) {
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
    }

    fun closePopup() {
        popupView?.let { removePopup(it) }
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
                        errorText = "离线预览不会加载外部网络资源"
                    }
                    return true
                }
                // http/https 一律留在内置浏览器里，不跳系统浏览器
                if (isWebViewInternalScheme(scheme)) return false
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
                val host = runCatching { Uri.parse(error.url).host.orEmpty() }
                    .getOrDefault("")
                if (isTrustedSslHost(host)) {
                    handler.proceed()
                } else {
                    handler.cancel()
                    if (!isPopup) {
                        errorText = "证书不受信任：$host\n内置浏览器只信任 A 机与 B 机的自签证书"
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (!isPopup && request?.isForMainFrame == true) {
                    errorText = error?.description?.toString() ?: "页面加载失败"
                }
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
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (isPopup) return
                progress = (newProgress.coerceIn(0, 100)) / 100f
                if (newProgress >= 100) {
                    canGoBack = view?.canGoBack() == true
                    canGoForward = view?.canGoForward() == true
                    errorText = null
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
        }
        return web
    }

    val webView = remember(initialUrl, offlineHtml) {
        createWebView(isPopup = false).also { frame.addView(it) }
    }

    // 离开页面：flush cookie 后再销毁 WebView，避免进程随后被杀时丢掉登录态
    DisposableEffect(webView) {
        onDispose {
            runCatching { cookieManager.flush() }
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

    fun loadHtml() {
        val source = offlineHtml ?: return
        errorText = null
        progress = 0f
        webView.loadDataWithBaseURL(policy.baseUrl, source, "text/html", "utf-8", null)
    }

    fun reloadPage() {
        if (offlineHtml != null) loadHtml() else webView.reload()
    }

    fun load(target: String) {
        val normalized = normalizeUrl(target)
        address = if (target.isBlank() || normalized == "about:blank") "" else normalized
        addressDirty = false
        errorText = null
        webView.loadUrl(normalized)
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
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundIconButton(
                Icons.Outlined.Refresh,
                onClick = { reloadPage() },
                size = 32.dp,
                iconSize = 15.dp,
            )
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

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = {
                    frame.also {
                        if (offlineHtml != null) loadHtml() else webView.loadUrl(initialUrl)
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
            errorText?.let { message ->
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
                            if (policy.isOfflinePreview) "预览打不开" else "页面打不开",
                            14f,
                            V2Colors.ink(.86f),
                            FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Label(
                            message,
                            10f,
                            V2Colors.ink(.48f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        GhostButton(
                            text = "重新加载",
                            onClick = { if (offlineHtml != null) loadHtml() else load(address) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Outlined.Refresh,
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(
                Icons.Outlined.ArrowBack,
                onClick = { if (webView.canGoBack()) webView.goBack() },
                size = 36.dp,
                iconSize = 16.dp,
                tint = if (canGoBack) V2Colors.ink(.62f) else V2Colors.ink(.20f),
            )
            RoundIconButton(
                Icons.Outlined.ArrowForward,
                onClick = { if (webView.canGoForward()) webView.goForward() },
                size = 36.dp,
                iconSize = 16.dp,
                tint = if (canGoForward) V2Colors.ink(.62f) else V2Colors.ink(.20f),
            )
            RoundIconButton(
                Icons.Outlined.Refresh,
                onClick = { reloadPage() },
                size = 36.dp,
                iconSize = 16.dp,
            )
            Spacer(Modifier.weight(1f))
            Label("内置浏览器", 9f, V2Colors.ink(.30f), mono = true)
            Spacer(Modifier.weight(1f))
            RoundIconButton(
                Icons.Outlined.Close,
                onClick = onBack,
                size = 36.dp,
                iconSize = 16.dp,
            )
        }
    }
}
