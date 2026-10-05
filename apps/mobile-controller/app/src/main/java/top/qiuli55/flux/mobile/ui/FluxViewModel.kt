package top.qiuli55.flux.mobile.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.first
import top.qiuli55.flux.mobile.data.ConnectionConfig
import top.qiuli55.flux.mobile.data.FluxApi
import top.qiuli55.flux.mobile.data.FluxEnv

/**
 * 所有 ViewModel 的公共基类。
 *
 * 每次操作都重新读一次配置（而不是在构造时缓存）：用户在设置页改了地址或令牌后，
 * 已经打开的页面下一次操作就该用新配置，不需要靠"重启应用"来生效。
 */
abstract class FluxViewModel : ViewModel() {

    protected suspend fun config(): ConnectionConfig = FluxEnv.settings.config.first()

    protected suspend fun api(): FluxApi = FluxEnv.api(config())
}