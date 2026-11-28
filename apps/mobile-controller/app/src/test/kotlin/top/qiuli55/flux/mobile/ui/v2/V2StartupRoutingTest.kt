package top.qiuli55.flux.mobile.ui.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.ConnectionConfig
import top.qiuli55.flux.mobile.data.DeviceInfo

/** 登录后即可进主界面：设备为空 / 离线都不能把整站挡在选设备页。 */
class V2StartupRoutingTest {

    private val signedIn = AccountSession(accessToken = "token")

    @Test
    fun `signed in without a device still enters the app`() {
        assertEquals(
            V2Boot.READY,
            resolveV2Boot(signedIn, ConnectionConfig()),
        )
    }

    @Test
    fun `signed in with an offline device still enters the app`() {
        val session = signedIn.copy(deviceId = "d1", deviceName = "书房电脑")
        assertEquals(V2Boot.READY, resolveV2Boot(session, ConnectionConfig()))
    }

    @Test
    fun `no session and no manual config goes to login`() {
        assertEquals(V2Boot.LOGIN, resolveV2Boot(null, ConnectionConfig()))
    }

    @Test
    fun `signed out but manual config still enters the app`() {
        val config = ConnectionConfig(baseUrl = "https://flux.example", token = "t")
        assertEquals(V2Boot.READY, resolveV2Boot(null, config))
    }

    @Test
    fun `prefers an online server device`() {
        val devices = listOf(
            DeviceInfo(deviceId = "desktop", name = "台式机", platform = "linux", status = "offline"),
            DeviceInfo(deviceId = "server", name = "常开服务器", platform = "server", status = "online"),
            DeviceInfo(deviceId = "laptop", name = "笔记本", platform = "windows", status = "online"),
        )
        assertEquals("server", pickDeviceForBinding(devices)?.deviceId)
    }

    @Test
    fun `falls back to any online device`() {
        val devices = listOf(
            DeviceInfo(deviceId = "desktop", name = "台式机", platform = "windows", status = "offline"),
            DeviceInfo(deviceId = "laptop", name = "笔记本", platform = "windows", status = "online"),
        )
        assertEquals("laptop", pickDeviceForBinding(devices)?.deviceId)
    }

    @Test
    fun `no online device means no auto binding`() {
        val devices = listOf(
            DeviceInfo(deviceId = "desktop", name = "台式机", platform = "windows", status = "offline"),
        )
        assertNull(pickDeviceForBinding(devices))
        assertTrue(!hasOnlineComputer(devices))
    }

    @Test
    fun `offline notice says the app is still usable`() {
        val notice = offlineDeviceNotice(
            DeviceInfo(deviceId = "d1", name = "书房电脑", platform = "windows", status = "offline"),
        )
        assertTrue(notice.contains("书房电脑"))
        assertTrue(notice.contains("当前离线"))
        assertTrue(notice.contains("仍可用"))
    }
}
