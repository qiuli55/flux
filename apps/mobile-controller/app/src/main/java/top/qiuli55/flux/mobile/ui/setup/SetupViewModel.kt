package top.qiuli55.flux.mobile.ui.setup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.ConnectionConfig
import top.qiuli55.flux.mobile.data.DshStatus
import top.qiuli55.flux.mobile.data.FluxApi
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.data.FluxEnv
import top.qiuli55.flux.mobile.data.HealthData
import top.qiuli55.flux.mobile.ui.FluxViewModel

/** 连接设置页的界面状态。 */
data class SetupUiState(
    val baseUrl: String = ConnectionConfig.DEFAULT_BASE_URL,
    val token: String = "",
    val testing: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val health: HealthData? = null,
    val dsh: DshStatus? = null,
    val agentCount: Int? = null,
    val savedHint: String? = null,
) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
}

/**
 * 连接设置：保存服务器地址与令牌，并用一次真实请求验证配置是否可用。
 *
 * "测试连接"不是只打一次 /health 就完事——顺便取回 Agent Runtime 状态与 Agent 数量：
 * 手机上要能一眼看出"连的是哪个实例、能不能真的跑 Agent"。
 */
class SetupViewModel : FluxViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val config = config()
            _state.value = _state.value.copy(baseUrl = config.baseUrl, token = config.token)
            if (config.isComplete) test()
        }
    }

    fun onBaseUrlChange(value: String) {
        _state.value = _state.value.copy(baseUrl = value, error = null, savedHint = null)
    }

    fun onTokenChange(value: String) {
        _state.value = _state.value.copy(token = value, error = null, savedHint = null)
    }

    fun test() {
        val current = _state.value
        if (!current.isComplete) {
            _state.value = current.copy(error = "服务器地址与访问令牌都要填")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(testing = true, error = null, health = null, dsh = null)
            try {
                val api = api()
                val health = api.health()
                val dsh = runCatching { api.dshStatus() }.getOrNull()
                val agents = runCatching { api.listAgents().size }.getOrNull()
                _state.value = _state.value.copy(
                    testing = false,
                    health = health,
                    dsh = dsh,
                    agentCount = agents,
                )
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(testing = false, error = describe(e))
            } catch (e: Exception) {
                _state.value = _state.value.copy(testing = false, error = e.message ?: "连接失败")
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        if (!current.isComplete) {
            _state.value = current.copy(error = "服务器地址与访问令牌都要填")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null, savedHint = null)
            try {
                // 地址归一化（补协议头、去尾斜杠）后再存：避免把 "flux.qiuli55.top/mobile/" 这种写法带进请求拼接。
                val normalized = FluxApi.normalizeBaseUrl(current.baseUrl)
                FluxEnv.settings.save(normalized, current.token)
                _state.value = _state.value.copy(
                    saving = false,
                    baseUrl = normalized,
                    savedHint = "已保存，后续请求都走 $normalized",
                )

                // 导航属于 UI 回调；即使导航本身发生异常，也不能让保存配置的协程把整个 App 带崩。
                try {
                    onSaved()
                } catch (e: Exception) {
                    _state.value = _state.value.copy(
                        error = "配置已保存，但页面跳转失败：${e.message ?: "未知错误"}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 之前这里没有异常边界：DataStore、URL 归一化或其他保存异常会直接从
                // viewModelScope 冒泡，导致 Android 进程崩溃。保存失败应该留在设置页展示错误。
                _state.value = _state.value.copy(
                    saving = false,
                    error = "保存配置失败：${e.message ?: e::class.simpleName ?: "未知错误"}",
                )
            }
        }
    }

    private fun describe(e: FluxApiException): String = when (e.code) {
        "network_error" -> e.message
        "unauthenticated" -> "令牌不对或已失效：${e.message}"
        else -> "${e.message}（${e.code}）"
    }
}
