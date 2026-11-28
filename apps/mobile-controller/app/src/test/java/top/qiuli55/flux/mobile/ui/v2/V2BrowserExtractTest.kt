// 「提取到创作」与网站登录态按账号隔离的纯逻辑用例。
//
// WebView 本身不在 JVM 上启动；这里锁住菜单文案、请求体、失败降级文案、
// 账号键 / 数据目录 suffix / 是否需要重启，以及 Google 拒绝 WebView 登录的识别。
package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.data.WebViewProfileAction
import top.qiuli55.flux.mobile.data.decideWebViewProfileAction
import top.qiuli55.flux.mobile.data.webViewDataDirectorySuffix
import top.qiuli55.flux.mobile.data.webViewProfileKey

class V2BrowserExtractTest {

    @Test
    fun `overflow menu labels are the two creation actions`() {
        assertEquals("提取为文档", EXTRACT_DOC_LABEL)
        assertEquals("保存为页面", EXTRACT_PAGE_LABEL)
        assertEquals("doc", V2BrowserExtractType.DOC.apiType)
        assertEquals("page", V2BrowserExtractType.PAGE.apiType)
        assertEquals("提取为文档", browserExtractTitle(V2BrowserExtractType.DOC))
        assertEquals("保存为页面", browserExtractTitle(V2BrowserExtractType.PAGE))
    }

    @Test
    fun `offline preview cannot be extracted or sent back`() {
        assertTrue(browserCanExtract(SITE_BROWSER_POLICY))
        assertFalse(browserCanExtract(OFFLINE_PREVIEW_BROWSER_POLICY))
        assertTrue(browserCanSendExtractToChat(SITE_BROWSER_POLICY, "task-1"))
        assertFalse(browserCanSendExtractToChat(SITE_BROWSER_POLICY, null))
        assertFalse(browserCanSendExtractToChat(SITE_BROWSER_POLICY, "  "))
        assertFalse(browserCanSendExtractToChat(OFFLINE_PREVIEW_BROWSER_POLICY, "task-1"))
    }

    @Test
    fun `evaluated javascript result is decoded or rejected safely`() {
        assertEquals("<html>正文</html>", decodeEvaluatedJavascriptString("\"<html>正文</html>\""))
        assertNull(decodeEvaluatedJavascriptString(null))
        assertNull(decodeEvaluatedJavascriptString(""))
        assertNull(decodeEvaluatedJavascriptString("null"))
        assertNull(decodeEvaluatedJavascriptString("undefined"))
        assertNull(decodeEvaluatedJavascriptString("42"))
        assertNull(decodeEvaluatedJavascriptString("not json"))
        assertNull(browserExtractHtml("\" \\n \""))
    }

    @Test
    fun `oversized client html is dropped so server can fetch by url`() {
        val oversized = "\"" + "x".repeat(MAX_EXTRACT_HTML_CHARS + 1) + "\""
        assertNull(browserExtractHtml(oversized))
        assertEquals("small", browserExtractHtml("\"small\""))
    }

    @Test
    fun `failure and fallback copy is chinese and actionable`() {
        assertEquals(
            "网络不可用，提取没有完成。检查网络后再试一次。",
            browserExtractErrorText(FluxApiException("network_error", "网络请求失败")),
        )
        assertTrue(
            browserExtractErrorText(FluxApiException("bad_request", "只支持提取 http / https 网页"))
                .contains("http"),
        )
        assertTrue(browserExtractErrorText(RuntimeException()).contains("重试"))
        assertTrue(browserExtractSuccessText(fallback = false, sentToChat = false).contains("创作"))
        assertTrue(browserExtractSuccessText(fallback = true, sentToChat = false).contains("链接"))
        assertTrue(browserExtractSuccessText(fallback = false, sentToChat = true).contains("发回当前对话"))
    }

    @Test
    fun `google webview login rejection is recognized and kept over generic load errors`() {
        val restricted = browserLoginRestrictionFailure(
            url = "https://accounts.google.com/signin/rejected?rrk=1",
            title = "无法登录",
            pageText = "Couldn't sign you in. This browser or app may not be secure.",
        )
        assertEquals(V2BrowserFailureKind.LOGIN_RESTRICTED, restricted?.kind)
        assertEquals("该网站限制应用内登录", restricted?.title)
        assertTrue("必须说明可改用系统浏览器", restricted?.message?.contains("系统浏览器") == true)
        assertFalse("限制登录不是重试能解决的", restricted?.canRetry == true)
        assertTrue(restricted?.canOpenExternally == true)
        assertTrue(restricted?.canShowPageAnyway == true)

        // 区分大小写不敏感，中文提示也能识别
        assertEquals(
            V2BrowserFailureKind.LOGIN_RESTRICTED,
            browserLoginRestrictionFailure(
                "https://accounts.google.com/v3/signin/identifier",
                "登录",
                "此浏览器或应用可能不安全，请改用其他浏览器。",
            )?.kind,
        )

        // URL 里的错误码也能识别（页面正文可能还没渲染出来）
        assertEquals(
            V2BrowserFailureKind.LOGIN_RESTRICTED,
            browserLoginRestrictionFailure(
                "https://accounts.google.com/signin?error=disallowed_useragent",
                "",
                "",
            )?.kind,
        )

        // 普通 Google 页面没有被拒绝的字样，不误报
        assertNull(
            browserLoginRestrictionFailure(
                "https://www.google.com/search?q=flux",
                "Google 搜索",
                "搜索结果",
            ),
        )
        // 非 Google 域即使出现同名字样也不当成登录限制
        assertNull(
            browserLoginRestrictionFailure(
                "https://example.com/post",
                "disallowed_useragent",
                "This browser or app may not be secure.",
            ),
        )

        val load = browserLoadFailure("https://accounts.google.com/x", false, "net::ERR_FAILED")
        assertEquals(restricted, preferredBrowserFailure(restricted, load))
    }

    @Test
    fun `webview profile key is stable per account and never the raw identity`() {
        val alice = webViewProfileKey("Alice", "alice@example.com")
        assertEquals("username 大小写变化不应换 profile", alice, webViewProfileKey(" alice ", ""))
        assertEquals(
            "缺 username 时 email 也应稳定",
            webViewProfileKey("", "Alice@Example.com"),
            webViewProfileKey("", " alice@example.com "),
        )
        assertTrue(alice.startsWith("acct-"))
        assertFalse("不能把账号明文写进目录名", alice.contains("alice"))
        assertTrue(
            "两个账号必须隔离",
            alice != webViewProfileKey("bob", "bob@example.com"),
        )
        assertEquals("未登录 / 游客共用 guest", "guest", webViewProfileKey(null, null))
        assertEquals("guest", webViewProfileKey("", "  "))
        // 账号云 userId 是首选稳定键：同一 username 被不同账号复用时也不会串目录
        assertTrue(
            webViewProfileKey(101L, "alice", "") != webViewProfileKey(102L, "alice", ""),
        )
        assertEquals(
            "userId 存在时 username 变化不换目录",
            webViewProfileKey(101L, "alice", ""),
            webViewProfileKey(101L, "alice-renamed", "new@example.com"),
        )
    }

    @Test
    fun `data directory suffix is never empty and has no path separators`() {
        val suffix = webViewDataDirectorySuffix(webViewProfileKey("alice", ""))
        assertTrue(suffix.startsWith("flux-acct-"))
        assertFalse(suffix.contains('/'))
        assertFalse(suffix.contains('\\'))
        assertTrue(suffix.length <= 64)
        assertEquals("flux-guest", webViewDataDirectorySuffix(""))
        // 意外键里的路径片段被替换掉，不能借 suffix 穿越目录
        assertFalse(webViewDataDirectorySuffix("../..").contains(".."))
        assertFalse(webViewDataDirectorySuffix("a/b").contains('/'))
    }

    @Test
    fun `profile switch restarts with suffix support and clears without it`() {
        val a = webViewProfileKey("alice", "")
        val b = webViewProfileKey("bob", "")
        assertEquals(
            WebViewProfileAction.KEEP,
            decideWebViewProfileAction(a, a, supportsDataDirectorySuffix = true),
        )
        assertEquals(
            WebViewProfileAction.RESTART,
            decideWebViewProfileAction(a, b, supportsDataDirectorySuffix = true),
        )
        assertEquals(
            "API 26 / 27 没有 suffix，切账号时清空 cookie / DOM Storage",
            WebViewProfileAction.CLEAR,
            decideWebViewProfileAction(a, b, supportsDataDirectorySuffix = false),
        )
        assertEquals(
            "不支持 suffix 时同一账号也不反复清空",
            WebViewProfileAction.KEEP,
            decideWebViewProfileAction(a, a, supportsDataDirectorySuffix = false),
        )
    }
}
