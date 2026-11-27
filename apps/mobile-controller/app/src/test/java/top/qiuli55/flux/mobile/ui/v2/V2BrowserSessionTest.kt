// 内置浏览器登录态 / 隔离策略的纯函数用例。
//
// 这些断言锁住的是「不能悄悄放宽」的边界：
//  - 站点会话必须持久化 cookie，并允许第三方 cookie（登录 / OAuth 需要）；
//  - 离线预览必须使用独立 origin、关闭第三方 cookie、阻断外部网络；
//  - 自签证书白名单只有 A / B 两台机器；
//  - http(s) 留在内置浏览器，只有非网页协议才交给系统；
//  - 「我的」入口打开的是空白站点会话。
package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2BrowserSessionTest {

    @Test
    fun `site session persists cookies and allows third party cookies`() {
        val policy = SITE_BROWSER_POLICY
        assertEquals(V2BrowserSession.SITE, policy.session)
        assertTrue("站点会话必须落盘 cookie", policy.persistsCookies)
        assertTrue("登录 / OAuth 需要第三方 cookie", policy.acceptThirdPartyCookies)
        assertTrue(policy.allowNetworkRequests)
        assertTrue("OAuth 弹窗需要多窗口", policy.supportsMultipleWindows)
        assertFalse(policy.isOfflinePreview)
    }

    @Test
    fun `offline preview is isolated from site cookies and network`() {
        val policy = OFFLINE_PREVIEW_BROWSER_POLICY
        assertEquals(V2BrowserSession.OFFLINE_PREVIEW, policy.session)
        assertFalse("预览不落盘站点登录态", policy.persistsCookies)
        assertFalse("预览不带第三方 cookie", policy.acceptThirdPartyCookies)
        assertFalse("预览不访问外部网络", policy.allowNetworkRequests)
        assertFalse("预览不开登录弹窗", policy.supportsMultipleWindows)
        assertTrue(policy.isOfflinePreview)
        assertEquals(OFFLINE_BASE_URL, policy.baseUrl)
    }

    @Test
    fun `html decides the session and plain url is always a site session`() {
        assertEquals(V2BrowserSession.OFFLINE_PREVIEW, browserPolicyFor("<html></html>").session)
        assertEquals(V2BrowserSession.SITE, browserPolicyFor(null).session)
    }

    @Test
    fun `offline preview blocks real site requests but keeps its own origin`() {
        val policy = OFFLINE_PREVIEW_BROWSER_POLICY
        assertTrue(shouldBlockPreviewRequest(policy, "https", "github.com"))
        assertTrue(shouldBlockPreviewRequest(policy, "http", "159.75.222.60"))
        assertFalse(shouldBlockPreviewRequest(policy, "https", PREVIEW_HOST))
        assertFalse(shouldBlockPreviewRequest(policy, "data", null))
        assertFalse(shouldBlockPreviewRequest(policy, "about", null))
        // file / content 也不放行，避免预览页读 App 私有文件
        assertTrue(shouldBlockPreviewRequest(policy, "file", null))
    }

    @Test
    fun `site session never blocks http or https requests`() {
        assertFalse(shouldBlockPreviewRequest(SITE_BROWSER_POLICY, "https", "github.com"))
        assertFalse(shouldBlockPreviewRequest(SITE_BROWSER_POLICY, "http", "159.75.222.60"))
    }

    @Test
    fun `ssl trust stays limited to the two self signed hosts`() {
        assertEquals(setOf("159.75.222.60", "115.159.125.254"), TRUSTED_SSL_HOSTS)
        assertTrue(isTrustedSslHost("159.75.222.60"))
        assertTrue(isTrustedSslHost("115.159.125.254"))
        assertFalse(isTrustedSslHost("159.75.222.61"))
        assertFalse(isTrustedSslHost("github.com"))
        assertFalse(isTrustedSslHost(""))
        assertFalse(isTrustedSslHost(null))
    }

    @Test
    fun `http and https stay in the in-app browser`() {
        assertTrue(isWebPageScheme("http"))
        assertTrue(isWebPageScheme("https"))
        assertTrue(isWebPageScheme("HTTPS"))
        assertFalse(isWebPageScheme("tel"))
        assertFalse(isWebPageScheme("mailto"))

        assertTrue(isWebViewInternalScheme("http"))
        assertTrue(isWebViewInternalScheme("HTTPS"))
        assertTrue(isWebViewInternalScheme("about"))
        assertTrue(isWebViewInternalScheme("data"))
        assertFalse(isWebViewInternalScheme("tel"))
    }

    @Test
    fun `only non web schemes are handed off to the system`() {
        assertFalse(shouldHandOffToSystem(SITE_BROWSER_POLICY, "http"))
        assertFalse(shouldHandOffToSystem(SITE_BROWSER_POLICY, "https"))
        assertTrue(shouldHandOffToSystem(SITE_BROWSER_POLICY, "tel"))
        assertTrue(shouldHandOffToSystem(SITE_BROWSER_POLICY, "mailto"))
        // GitHub 回跳依赖系统分发 flux:// 给 MainActivity
        assertTrue(shouldHandOffToSystem(SITE_BROWSER_POLICY, "flux"))
        // 离线预览一律不外跳
        assertFalse(shouldHandOffToSystem(OFFLINE_PREVIEW_BROWSER_POLICY, "mailto"))
    }

    @Test
    fun `profile entry opens a blank persistent site session`() {
        assertEquals("about:blank", blankBrowserSessionUrl())
        assertEquals("about:blank", browserStartUrl(null))
        assertEquals("about:blank", browserStartUrl("   "))
        assertEquals("", browserAddressText(null))
        assertEquals("", browserAddressText("  "))
        // 空白会话不显示 about:blank，输入网址后才换成真实地址
        assertEquals("", browserAddressText("about:blank"))
        assertEquals("https://flux.qiuli55.top/x", browserAddressText("flux.qiuli55.top/x"))
        assertEquals(V2BrowserSession.SITE, browserPolicyFor(null).session)
    }
}
