// 内置浏览器打磨部分的纯函数用例：UA 选择、返回键优先级、下载 URL / 文件名、
// 权限名称、最近访问的去重与上限。
//
// 这些逻辑都从 WebView 回调里抽出来，保证「不静默授权、不接危险下载、
// 硬件返回不直接退出登录页」这些边界能在 JVM 上锁住。
package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2BrowserPolishTest {

    @Test
    fun `site session defaults to desktop UA and can switch back to mobile`() {
        assertEquals(DESKTOP_USER_AGENT, browserUserAgent(SITE_BROWSER_POLICY, desktopMode = true))
        assertEquals(MOBILE_USER_AGENT, browserUserAgent(SITE_BROWSER_POLICY, desktopMode = false))
        assertTrue(DESKTOP_USER_AGENT.contains("Windows NT"))
        assertTrue(MOBILE_USER_AGENT.contains("Android"))
        // 离线预览保持 WebView 默认 UA，不参与站点兼容 hack
        assertNull(browserUserAgent(OFFLINE_PREVIEW_BROWSER_POLICY, desktopMode = true))
        assertNull(browserUserAgent(OFFLINE_PREVIEW_BROWSER_POLICY, desktopMode = false))
    }

    @Test
    fun `hardware back closes overlays before webview history then exits`() {
        assertEquals(
            V2BrowserBackAction.CLOSE_JS_DIALOG,
            decideBrowserBack(jsDialogOpen = true, permissionOpen = true, popupOpen = true, canGoBack = true),
        )
        assertEquals(
            V2BrowserBackAction.CLOSE_PERMISSION,
            decideBrowserBack(permissionOpen = true, popupOpen = true, findOpen = true, canGoBack = true),
        )
        assertEquals(
            V2BrowserBackAction.CLOSE_POPUP,
            decideBrowserBack(popupOpen = true, findOpen = true, historyOpen = true, canGoBack = true),
        )
        assertEquals(
            V2BrowserBackAction.CLOSE_FIND,
            decideBrowserBack(findOpen = true, historyOpen = true, canGoBack = true),
        )
        assertEquals(
            V2BrowserBackAction.CLOSE_HISTORY,
            decideBrowserBack(historyOpen = true, canGoBack = true),
        )
        // 没有弹层时先让 WebView 后退，只有退无可退才退出浏览器页
        assertEquals(V2BrowserBackAction.WEBVIEW_BACK, decideBrowserBack(canGoBack = true))
        assertEquals(V2BrowserBackAction.EXIT, decideBrowserBack(canGoBack = false))
        assertEquals(V2BrowserBackAction.EXIT, decideBrowserBack())
    }

    @Test
    fun `downloads only allow http and https in the site session`() {
        assertTrue(isDownloadUrlAllowed(SITE_BROWSER_POLICY, "https://example.com/a.pdf"))
        assertTrue(isDownloadUrlAllowed(SITE_BROWSER_POLICY, "http://159.75.222.60/a.zip"))
        assertFalse(isDownloadUrlAllowed(SITE_BROWSER_POLICY, "data:text/html,hi"))
        assertFalse(isDownloadUrlAllowed(SITE_BROWSER_POLICY, "blob:https://example.com/x"))
        assertFalse(isDownloadUrlAllowed(SITE_BROWSER_POLICY, "file:///etc/passwd"))
        assertFalse(isDownloadUrlAllowed(SITE_BROWSER_POLICY, "content://media/1"))
        assertFalse(isDownloadUrlAllowed(SITE_BROWSER_POLICY, "javascript:alert(1)"))
        assertFalse(isDownloadUrlAllowed(SITE_BROWSER_POLICY, "https://flux-preview.local/x"))
        assertFalse(isDownloadUrlAllowed(SITE_BROWSER_POLICY, ""))
        assertFalse(isDownloadUrlAllowed(SITE_BROWSER_POLICY, null))
        // 离线预览一律不下载
        assertFalse(isDownloadUrlAllowed(OFFLINE_PREVIEW_BROWSER_POLICY, "https://example.com/a.pdf"))
    }

    @Test
    fun `download filename prefers content disposition and never escapes the directory`() {
        assertEquals(
            "report.pdf",
            downloadFileName(
                "attachment; filename=\"report.pdf\"",
                "https://example.com/x",
                "application/pdf",
            ),
        )
        assertEquals(
            "报告.pdf",
            downloadFileName(
                "attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf",
                "https://example.com/x",
                null,
            ),
        )
        assertEquals(
            "file.zip",
            downloadFileName(null, "https://example.com/a/file.zip?token=1", null),
        )
        // 路径穿越只保留最后一段
        assertEquals(
            "passwd",
            downloadFileName("attachment; filename=\"../../etc/passwd\"", "https://x/y", null),
        )
        // URL 没有文件名时用 MIME 补一个可识别的后缀
        assertEquals(
            "download.png",
            downloadFileName(null, "https://example.com/", "image/png"),
        )
        assertEquals(
            "download.bin",
            downloadFileName(null, "https://example.com/", "application/octet-stream"),
        )
        assertEquals("download", downloadFileName(null, "https://example.com/", null))

        val sanitized = downloadFileName("filename=\"a/b\\\\c.txt\"", null, null)
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\\'))
    }

    @Test
    fun `history keeps newest first dedupes by url and has a hard limit`() {
        val first = BrowserHistoryEntry("https://a.example/1", "A", 1)
        val second = BrowserHistoryEntry("https://b.example/2", "B", 2)
        var entries = addBrowserHistory(emptyList(), first)
        entries = addBrowserHistory(entries, second)
        assertEquals(listOf(second.url, first.url), entries.map { it.url })

        // 同一 URL 再访问：更新标题 / 时间并移到最前，不产生重复
        val revisited = first.copy(title = "A2", visitedAt = 3)
        entries = addBrowserHistory(entries, revisited)
        assertEquals(listOf(first.url, second.url), entries.map { it.url })
        assertEquals("A2", entries.first().title)
        assertEquals(3L, entries.first().visitedAt)

        // 超过上限后只保留最新的
        var capped = emptyList<BrowserHistoryEntry>()
        for (index in 0 until BROWSER_HISTORY_LIMIT + 30) {
            capped = addBrowserHistory(
                capped,
                BrowserHistoryEntry("https://x.example/$index", "", index.toLong()),
            )
        }
        assertEquals(BROWSER_HISTORY_LIMIT, capped.size)
        assertEquals("https://x.example/${BROWSER_HISTORY_LIMIT + 29}", capped.first().url)
        assertEquals("https://x.example/30", capped.last().url)

        // about:blank / 空 URL 不写历史；limit 0 返回空
        assertEquals(capped, addBrowserHistory(capped, BrowserHistoryEntry("about:blank", "", 9)))
        assertEquals(capped, addBrowserHistory(capped, BrowserHistoryEntry("", "", 9)))
        assertTrue(addBrowserHistory(entries, first, limit = 0).isEmpty())
    }

    @Test
    fun `history json round trips and tolerates corrupted storage`() {
        val entries = listOf(
            BrowserHistoryEntry("https://example.com/a?x=1", "标题 \"quoted\"", 42),
            BrowserHistoryEntry("https://example.com/b", "", 43),
        )
        val raw = encodeBrowserHistory(entries)
        assertEquals(entries, decodeBrowserHistory(raw))

        assertTrue(decodeBrowserHistory(null).isEmpty())
        assertTrue(decodeBrowserHistory("").isEmpty())
        assertTrue(decodeBrowserHistory("not json").isEmpty())
        assertTrue(decodeBrowserHistory("{\"unexpected\":true}").isEmpty())

        // 损坏 / 空 URL 的条目在解码时被过滤
        val withBlank = encodeBrowserHistory(
            listOf(BrowserHistoryEntry("", "x", 1), entries[0]),
        )
        assertEquals(listOf(entries[0]), decodeBrowserHistory(withBlank))
    }

    @Test
    fun `history title falls back to host when the page has no title`() {
        assertEquals(
            "Custom title",
            browserHistoryTitle(BrowserHistoryEntry("https://example.com/a", "Custom title", 1)),
        )
        assertEquals(
            "example.com",
            browserHistoryTitle(BrowserHistoryEntry("https://example.com/a", "   ", 1)),
        )
    }

    @Test
    fun `history only records real web pages from the site session`() {
        assertTrue(shouldRecordHistory(SITE_BROWSER_POLICY, "https://example.com/a"))
        assertTrue(shouldRecordHistory(SITE_BROWSER_POLICY, "http://159.75.222.60/a"))
        assertFalse(shouldRecordHistory(SITE_BROWSER_POLICY, "about:blank"))
        assertFalse(shouldRecordHistory(SITE_BROWSER_POLICY, "file:///data/x"))
        assertFalse(shouldRecordHistory(SITE_BROWSER_POLICY, "data:text/html,hi"))
        assertFalse(shouldRecordHistory(SITE_BROWSER_POLICY, "https://flux-preview.local/x"))
        // 离线预览不写站点历史
        assertFalse(shouldRecordHistory(OFFLINE_PREVIEW_BROWSER_POLICY, "https://example.com/a"))
    }

    @Test
    fun `permission labels describe camera microphone and unknown requests`() {
        assertEquals(
            "摄像头、麦克风",
            webPermissionLabel(
                arrayOf(
                    "android.webkit.resource.VIDEO_CAPTURE",
                    "android.webkit.resource.AUDIO_CAPTURE",
                ),
            ),
        )
        assertEquals(
            "摄像头",
            webPermissionLabel(
                arrayOf(
                    "android.webkit.resource.VIDEO_CAPTURE",
                    "android.webkit.resource.VIDEO_CAPTURE",
                ),
            ),
        )
        assertEquals(
            "页面请求的权限",
            webPermissionLabel(arrayOf("android.webkit.resource.SOMETHING_NEW")),
        )
        assertEquals("页面请求的权限", webPermissionLabel(emptyArray<String>()))
        assertEquals("页面请求的权限", webPermissionLabel(null))
    }

    @Test
    fun `http url host rejects non web schemes`() {
        assertEquals("example.com", httpUrlHost("https://Example.com/a?b=1"))
        assertEquals("159.75.222.60", httpUrlHost("http://159.75.222.60:8080/x"))
        assertNull(httpUrlHost("mailto:a@example.com"))
        assertNull(httpUrlHost("flux://auth?code=1"))
        assertNull(httpUrlHost(""))
        assertNull(httpUrlHost(null))
    }
}
