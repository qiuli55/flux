// 内置浏览器「工作区」打磨的纯函数用例：失败态分类与优先级、进度不冲掉错误、
// 空白入口恢复上次页面、可分享链接、页内查找无结果、下载失败原因。
//
// 这些边界都在 WebView 回调之外，能在 JVM 上稳定锁住「离开再回来不丢页」和
// 「错误页不会被 progress=100 冲掉」。
package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2BrowserWorkspaceTest {

    @Test
    fun `progress 100 updates the bar but never clears an existing failure`() {
        val ssl = browserSslFailure("github.com", "https://github.com/login")
        val started = applyBrowserProgress(
            V2BrowserProgressState(progress = 0f, failure = ssl),
            percent = 40,
        )
        assertEquals(0.4f, started.progress, 0.0001f)
        assertEquals(ssl, started.failure)

        // 之前这里会把 errorText 置空，SSL / HTTP 失败页刚出现就被冲掉
        val finished = applyBrowserProgress(started, percent = 100)
        assertEquals(1f, finished.progress, 0.0001f)
        assertEquals(ssl, finished.failure)

        // 进度越界也要夹紧，不能让进度条宽度为负 / 溢出
        assertEquals(0f, applyBrowserProgress(started, -5).progress, 0.0001f)
        assertEquals(1f, applyBrowserProgress(started, 180).progress, 0.0001f)
    }

    @Test
    fun `specific ssl and http failures are not overwritten by generic load errors`() {
        val ssl = browserSslFailure("github.com", "https://github.com/")
        val http = browserHttpFailure("https://example.com/missing", 404)
        val load = browserLoadFailure("https://example.com/", false, "net::ERR_FAILED")

        // SSL 优先于随后补发的通用加载错误
        assertEquals(ssl, preferredBrowserFailure(ssl, load))
        // HTTP 优先于通用加载错误，但 SSL 更具体
        assertEquals(http, preferredBrowserFailure(http, load))
        assertEquals(ssl, preferredBrowserFailure(http, ssl))
        // 没有具体错误时接受新的通用错误；空 incoming 保留现状
        assertEquals(load, preferredBrowserFailure(null, load))
        assertEquals(load, preferredBrowserFailure(load, null))
        assertNull(preferredBrowserFailure(null, null))
    }

    @Test
    fun `network failures ask the user to check connectivity`() {
        val offline = browserLoadFailure(
            failedUrl = "https://example.com/a",
            networkUnavailable = true,
            description = "net::ERR_NAME_NOT_RESOLVED",
        )
        assertEquals(V2BrowserFailureKind.NO_NETWORK, offline.kind)
        assertEquals("网络不可用", offline.title)
        assertTrue(offline.message.contains("网络"))
        assertTrue(offline.canRetry)
        assertTrue(offline.canOpenExternally)
        assertEquals("net::ERR_NAME_NOT_RESOLVED", offline.detail)

        val generic = browserLoadFailure("https://example.com/a", false, null)
        assertEquals(V2BrowserFailureKind.LOAD, generic.kind)
        assertEquals("页面打不开", generic.title)
        assertTrue(generic.canRetry)
        assertNull(generic.detail)
        // 非网页 URL 不提供「在系统浏览器打开」
        assertFalse(browserLoadFailure("flux://callback", false, null).canOpenExternally)
        // 预览的合成域名也不该被当成可外开的链接
        assertFalse(
            browserLoadFailure("https://flux-preview.local/", false, null).canOpenExternally,
        )

        assertTrue(isBrowserNetworkErrorCode(-2)) // ERROR_HOST_LOOKUP
        assertTrue(isBrowserNetworkErrorCode(-6)) // ERROR_CONNECT
        assertTrue(isBrowserNetworkErrorCode(-8)) // ERROR_TIMEOUT
        assertFalse(isBrowserNetworkErrorCode(-1)) // ERROR_UNKNOWN
        assertFalse(isBrowserNetworkErrorCode(0))
    }

    @Test
    fun `http failures show status and allow viewing the server page`() {
        val notFound = browserHttpFailure("https://example.com/missing", 404)
        assertEquals(V2BrowserFailureKind.HTTP, notFound.kind)
        assertEquals("网页返回 404", notFound.title)
        assertEquals(404, notFound.statusCode)
        assertTrue(notFound.message.contains("页面不存在"))
        assertTrue(notFound.canRetry)
        assertTrue(notFound.canOpenExternally)
        assertTrue("4xx / 5xx 页面可能仍有内容", notFound.canShowPageAnyway)

        // 没有中文说明的状态码仍然显示状态码，不吞掉
        val teapot = browserHttpFailure("https://example.com/tea", 418)
        assertTrue(teapot.message.contains("HTTP 418"))
        assertNull(httpStatusReason(418))
        assertEquals("页面不存在", httpStatusReason(404))
        assertEquals("服务暂不可用", httpStatusReason(503))
        assertEquals("请求过于频繁", httpStatusReason(429))
    }

    @Test
    fun `ssl failures name the host and keep the trust boundary explicit`() {
        val ssl = browserSslFailure("bad.example", "https://bad.example/login")
        assertEquals(V2BrowserFailureKind.SSL, ssl.kind)
        assertEquals("证书不受信任", ssl.title)
        assertTrue(ssl.message.contains("bad.example"))
        assertTrue("文案必须说明只信任 A / B 机", ssl.message.contains("A 机与 B 机"))
        assertTrue(ssl.canOpenExternally)

        val unknown = browserSslFailure(null, null)
        assertTrue(unknown.message.contains("该网站"))
        assertFalse(unknown.canOpenExternally)
    }

    @Test
    fun `preview blocked failure has no retry or external action`() {
        val blocked = browserPreviewBlockedFailure()
        assertEquals(V2BrowserFailureKind.PREVIEW_BLOCKED, blocked.kind)
        assertEquals("预览已拦截外部请求", blocked.title)
        assertFalse(blocked.canRetry)
        assertFalse(blocked.canOpenExternally)
        assertFalse(blocked.canShowPageAnyway)
    }

    @Test
    fun `blank entry restores the last page while explicit links win`() {
        val saved = "https://example.com/inbox"

        // 「我的」空白入口：恢复上次页面
        assertTrue(shouldRestoreBrowserSession(SITE_BROWSER_POLICY, "about:blank"))
        assertTrue(shouldRestoreBrowserSession(SITE_BROWSER_POLICY, "  "))
        assertTrue(shouldRestoreBrowserSession(SITE_BROWSER_POLICY, null))
        assertEquals(saved, browserEntryUrl(SITE_BROWSER_POLICY, "about:blank", saved))
        // 没有可恢复页面时仍是空白会话
        assertEquals("about:blank", browserEntryUrl(SITE_BROWSER_POLICY, null, null))

        // 点开具体链接：始终以链接为准，不被上次页面顶掉
        assertFalse(shouldRestoreBrowserSession(SITE_BROWSER_POLICY, "https://other.example/x"))
        assertEquals(
            "https://other.example/x",
            browserEntryUrl(SITE_BROWSER_POLICY, "https://other.example/x", saved),
        )

        // 只恢复真实网页；空 / 本地 / 预览地址不恢复
        assertNull(restorableBrowserUrl(SITE_BROWSER_POLICY, "about:blank"))
        assertNull(restorableBrowserUrl(SITE_BROWSER_POLICY, "file:///data/x"))
        assertNull(restorableBrowserUrl(SITE_BROWSER_POLICY, "data:text/html,hi"))
        assertNull(restorableBrowserUrl(SITE_BROWSER_POLICY, "https://flux-preview.local/x"))
        assertEquals(
            saved,
            restorableBrowserUrl(SITE_BROWSER_POLICY, "  $saved  "),
        )

        // 离线预览不恢复站点页面，也不读站点快照
        assertFalse(shouldRestoreBrowserSession(OFFLINE_PREVIEW_BROWSER_POLICY, "about:blank"))
        assertEquals(
            "about:blank",
            browserEntryUrl(OFFLINE_PREVIEW_BROWSER_POLICY, "about:blank", saved),
        )
    }

    @Test
    fun `retry targets the current page before the entry url`() {
        assertEquals(
            "https://example.com/a",
            browserRetryUrl(
                SITE_BROWSER_POLICY,
                pageUrl = "https://example.com/a",
                entryUrl = "https://example.com/",
                addressText = "",
            ),
        )
        // 页面 URL 不可用时退回进入地址
        assertEquals(
            "https://example.com/",
            browserRetryUrl(
                SITE_BROWSER_POLICY,
                pageUrl = "about:blank",
                entryUrl = "https://example.com/",
                addressText = "",
            ),
        )
        assertNull(
            "离线预览由 loadHtml 重试",
            browserRetryUrl(
                OFFLINE_PREVIEW_BROWSER_POLICY,
                pageUrl = null,
                entryUrl = null,
                addressText = null,
            ),
        )
    }

    @Test
    fun `shareable link prefers the real page and rejects blank preview and local urls`() {
        assertEquals(
            "https://example.com/real",
            browserShareableUrl(
                SITE_BROWSER_POLICY,
                pageUrl = "https://example.com/real",
                addressText = "https://example.com/half-typed",
            ),
        )
        // 地址栏还没提交时也能复制 / 分享即将打开的链接
        assertEquals(
            "https://example.com/typed",
            browserShareableUrl(
                SITE_BROWSER_POLICY,
                pageUrl = "about:blank",
                addressText = "example.com/typed",
            ),
        )
        assertNull(browserShareableUrl(SITE_BROWSER_POLICY, "about:blank", ""))
        assertNull(browserShareableUrl(SITE_BROWSER_POLICY, "about:blank", "   "))
        assertNull(browserShareableUrl(SITE_BROWSER_POLICY, "data:text/html,hi", null))
        assertNull(browserShareableUrl(SITE_BROWSER_POLICY, "file:///data/x", null))
        // 离线预览没有可对外的链接
        assertNull(
            browserShareableUrl(
                OFFLINE_PREVIEW_BROWSER_POLICY,
                pageUrl = "https://example.com/real",
                addressText = "https://example.com/real",
            ),
        )
    }

    @Test
    fun `navigation ignores an empty address instead of blanking the page`() {
        assertNull(browserNavigationTarget(null))
        assertNull(browserNavigationTarget(""))
        assertNull(browserNavigationTarget("   "))
        assertEquals("https://example.com/a", browserNavigationTarget("example.com/a"))
        assertEquals("http://159.75.222.60/x", browserNavigationTarget("http://159.75.222.60/x"))
        assertEquals("about:blank", browserNavigationTarget("about:blank"))
    }

    @Test
    fun `find status explains zero matches`() {
        assertNull(browserFindStatus("", 0, 0))
        assertNull(browserFindStatus("   ", 0, 0))
        assertEquals("无结果", browserFindStatus("needle", 0, 0))
        assertEquals("2/7", browserFindStatus("needle", 2, 7))
        // 越界的序号夹回 1..total，不显示 0/7 或 8/7
        assertEquals("1/7", browserFindStatus("needle", 0, 7))
        assertEquals("7/7", browserFindStatus("needle", 9, 7))
    }

    @Test
    fun `download failure reasons are human readable`() {
        assertEquals("存储空间不足", downloadFailureReason(1006))
        assertEquals("下载过程中网络数据出错", downloadFailureReason(1004))
        assertEquals("系统阻止了这次下载", downloadFailureReason(1010))
        assertNull(downloadFailureReason(0))
        assertNull(downloadFailureReason(9999))
    }
}
