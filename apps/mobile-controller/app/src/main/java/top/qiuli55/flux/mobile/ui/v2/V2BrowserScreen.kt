package top.qiuli55.flux.mobile.ui.v2

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * App 内嵌浏览器：对话里点开的网址一律在这里打开，不跳系统浏览器。
 *
 * 用途是预览完整可交互的 HTML Demo（含 JS），因此 WebView 开 JavaScript 与 DOM Storage。
 * 自签证书只对 A 机（159.75.222.60）与 B 机（115.159.125.254）放行，其他站点证书异常
 * 仍然拦截并提示——不因为内嵌浏览器降低整机安全边界。
 */
private val TRUSTED_SSL_HOSTS = setOf("159.75.222.60", "115.159.125.254")

/** 离线 HTML 的基准地址：让 WebView 认为是同源 https 页面，localStorage 等 API 可用。 */
private const val OFFLINE_BASE_URL = "https://flux-preview.local/"

/** 输入框里没写协议时默认补 https://；本机 IP 的 http 地址原样保留（单测覆盖）。 */
internal fun normalizeUrl(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return "about:blank"
    if (text.startsWith("http://") || text.startsWith("https://") || text == "about:blank") {
        return text
    }
    return "https://$text"
}

/**
 * 内嵌浏览器页面。
 *
 * [html] 不为空时走离线模式：直接渲染这段单文件 HTML（设计稿网页预览），不发起网络请求，
 * 地址栏换成静态标题。
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
    val initialUrl = remember(url) { normalizeUrl(url) }
    val offlineHtml = html

    var address by remember(initialUrl) { mutableStateOf(initialUrl) }
    var addressDirty by remember(initialUrl) { mutableStateOf(false) }
    var progress by remember(initialUrl) { mutableStateOf(0f) }
    var canGoBack by remember(initialUrl) { mutableStateOf(false) }
    var canGoForward by remember(initialUrl) { mutableStateOf(false) }
    var errorText by remember(initialUrl) { mutableStateOf<String?>(null) }

    val webView = remember(initialUrl, offlineHtml) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                setSupportMultipleWindows(false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val target = request?.url ?: return false
                    val scheme = target.scheme.orEmpty().lowercase()
                    if (scheme == "http" || scheme == "https") {
                        return false
                    }
                    // 非网页协议（tel / mailto / 第三方 App scheme）交回系统处理
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, target))
                    }
                    return true
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    val host = runCatching { Uri.parse(error.url).host.orEmpty() }
                        .getOrDefault("")
                    if (host in TRUSTED_SSL_HOSTS) {
                        handler.proceed()
                    } else {
                        handler.cancel()
                        errorText = "证书不受信任：$host\n内置浏览器只信任 A 机与 B 机的自签证书"
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        errorText = error?.description?.toString() ?: "页面加载失败"
                    }
                }

                override fun doUpdateVisitedHistory(
                    view: WebView?,
                    url: String?,
                    isReload: Boolean,
                ) {
                    canGoBack = view?.canGoBack() == true
                    canGoForward = view?.canGoForward() == true
                    if (!addressDirty && !url.isNullOrBlank()) {
                        address = url
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = (newProgress.coerceIn(0, 100)) / 100f
                    if (newProgress >= 100) {
                        canGoBack = view?.canGoBack() == true
                        canGoForward = view?.canGoForward() == true
                        errorText = null
                    }
                }
            }
        }
    }

    // 离开页面就销毁 WebView，避免后台继续跑 JS 与占用内存
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    fun loadHtml() {
        val source = offlineHtml ?: return
        errorText = null
        progress = 0f
        webView.loadDataWithBaseURL(OFFLINE_BASE_URL, source, "text/html", "utf-8", null)
    }

    fun reloadPage() {
        if (offlineHtml != null) loadHtml() else webView.reload()
    }

    fun load(target: String) {
        val normalized = normalizeUrl(target)
        address = normalized
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
                    webView.also {
                        if (offlineHtml != null) loadHtml() else it.loadUrl(initialUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
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
                        Label("预览打不开", 14f, V2Colors.ink(.86f), FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Label(message, 10f, V2Colors.ink(.48f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
