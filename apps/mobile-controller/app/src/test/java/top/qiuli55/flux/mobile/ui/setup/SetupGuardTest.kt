package top.qiuli55.flux.mobile.ui.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动连接的「测试 / 保存」入口互斥。
 *
 * 按钮禁用态要等一次重组，连点第二下时状态还没更新；入口必须用当前状态同步拦住，
 * 否则会同时发出两次健康检查或两次保存。
 */
class SetupGuardTest {

    @Test
    fun `idle form accepts the action`() {
        assertFalse(setupActionInFlight(testing = false, saving = false))
    }

    @Test
    fun `test in flight blocks a second tap of either action`() {
        assertTrue(setupActionInFlight(testing = true, saving = false))
    }

    @Test
    fun `save in flight blocks a second tap of either action`() {
        assertTrue(setupActionInFlight(testing = false, saving = true))
    }

    @Test
    fun `both flags set still blocks`() {
        assertTrue(setupActionInFlight(testing = true, saving = true))
    }
}
