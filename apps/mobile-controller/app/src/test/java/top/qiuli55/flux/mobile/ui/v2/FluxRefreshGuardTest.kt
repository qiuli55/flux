package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.AccountSession

/**
 * 刷新准入与静默错误抑制的纯 JVM 测试（不依赖 Android / Compose）。
 *
 * 背景：设备选择页会把 state.error 直接渲染成红色错误条。账号登录后还没选设备时，
 * ON_RESUME 的静默刷新会拿上一次手动连接的旧令牌打 /mobile，401 的
 * 「缺少或无效的访问令牌」就出现在设备选择页上。
 *
 * 覆盖：
 *  - 正常路径：READY（手动连接 / 已选设备）允许刷新，显式刷新失败会展示错误；
 *  - 错误路径：非 READY 跳过刷新且不创建 FluxApi 请求；静默刷新 401 不写 state.error。
 */
class FluxRefreshGuardTest {

    private fun account(deviceId: String = "") = AccountSession(
        accessToken = "account-access-token",
        refreshToken = "account-refresh-token",
        username = "tester",
        email = "tester@example.com",
        deviceId = deviceId,
        deviceName = if (deviceId.isBlank()) "" else "MacBook",
    )

    @Test
    fun `ready with selected device allows refresh`() {
        val gate = fluxRefreshGuard(V2Boot.READY, account(deviceId = "device-1"))
        assertTrue(gate.allowed)
        assertEquals("", gate.reason)
    }

    @Test
    fun `ready manual connection allows refresh`() {
        assertTrue(fluxRefreshGuard(V2Boot.READY, null).allowed)
    }

    @Test
    fun `device picker with account but no device skips refresh before any Flux call`() {
        var fluxCalls = 0

        // 与 V2AppViewModel.refresh() 相同的入口判断：准入失败时不会走到 api()。
        fun refreshIfAllowed(boot: V2Boot, account: AccountSession?) {
            if (fluxRefreshGuard(boot, account).allowed) fluxCalls++
        }

        refreshIfAllowed(V2Boot.DEVICES, account(deviceId = ""))
        assertEquals(0, fluxCalls)
        val gate = fluxRefreshGuard(V2Boot.DEVICES, account(deviceId = ""))
        assertFalse(gate.allowed)
        assertTrue(gate.reason.contains("no selected device"))
    }

    @Test
    fun `ready state still skips when signed in without a device`() {
        assertFalse(fluxRefreshGuard(V2Boot.READY, account(deviceId = "")).allowed)
    }

    @Test
    fun `login screen skips refresh`() {
        val gate = fluxRefreshGuard(V2Boot.LOGIN, null)
        assertFalse(gate.allowed)
        assertTrue(gate.reason.contains("LOGIN"))
    }

    @Test
    fun `loading screen skips refresh`() {
        assertFalse(fluxRefreshGuard(V2Boot.LOADING, null).allowed)
    }

    @Test
    fun `quiet refresh 401 does not write state error`() {
        val failed = V2AppState(
            boot = V2Boot.READY,
            refreshing = true,
            error = null,
        ).afterRefreshFailure(quiet = true, message = "缺少或无效的访问令牌")

        assertNull(failed.error)
        assertFalse(failed.refreshing)
    }

    @Test
    fun `quiet refresh failure keeps a previous explicit error untouched`() {
        val failed = V2AppState(
            boot = V2Boot.READY,
            refreshing = true,
            error = "之前手动操作的错误",
        ).afterRefreshFailure(quiet = true, message = "缺少或无效的访问令牌")

        assertEquals("之前手动操作的错误", failed.error)
    }

    @Test
    fun `explicit refresh failure is shown to the user`() {
        val failed = V2AppState(
            boot = V2Boot.READY,
            refreshing = true,
        ).afterRefreshFailure(quiet = false, message = "缺少或无效的访问令牌")

        assertEquals("缺少或无效的访问令牌", failed.error)
        assertFalse(failed.refreshing)
    }
}
