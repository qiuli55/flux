package top.qiuli55.flux.mobile.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.CloudApi
import top.qiuli55.flux.mobile.data.CloudApiException
import top.qiuli55.flux.mobile.data.DeviceInfo
import top.qiuli55.flux.mobile.data.FluxEnv

data class DeviceListUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val devices: List<DeviceInfo> = emptyList(),
    val account: AccountSession? = null,
    val error: String? = null,
)

class DeviceListViewModel : ViewModel() {

    private var cloud = CloudApi()
    private val _state = MutableStateFlow(DeviceListUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load(manual: Boolean = false) {
        _state.value = _state.value.copy(
            loading = !manual && _state.value.devices.isEmpty(),
            refreshing = manual,
            error = null,
        )
        viewModelScope.launch {
            try {
                val session = FluxEnv.settings.account.first()
                if (session == null) {
                    _state.value = _state.value.copy(loading = false, refreshing = false)
                    return@launch
                }
                cloud = CloudApi(session.cloudUrl)
                val fresh = refreshIfNeeded(session)
                val devices = cloud.devices(fresh.accessToken)
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    devices = devices,
                    account = fresh,
                    error = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    error = readable(e),
                )
            }
        }
    }

    fun select(device: DeviceInfo, onSelected: () -> Unit) {
        if (!device.isOnline) {
            _state.value = _state.value.copy(error = "设备离线：先在 ${device.name} 上打开 Flux 并登录同一账号")
            return
        }
        viewModelScope.launch {
            val session = FluxEnv.settings.account.first() ?: return@launch
            FluxEnv.settings.saveDevice(device.deviceId, device.name)
            val selected = session.copy(deviceId = device.deviceId, deviceName = device.name)
            // 账号模式把 Flux 基址切到云端中转，令牌用账号 access token；
            // 现有页面与 ViewModel 都只读 ConnectionConfig，不需要感知账号存在。
            FluxEnv.settings.save(selected.proxyBaseUrl, selected.accessToken)
            _state.value = _state.value.copy(account = selected, error = null)
            onSelected()
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            FluxEnv.settings.clearAccount()
            onLoggedOut()
        }
    }

    private suspend fun refreshIfNeeded(session: AccountSession): AccountSession {
        if (!session.isExpiringSoon || session.refreshToken.isBlank()) return session
        return try {
            val refreshed = cloud.refresh(session.refreshToken)
            val merged = refreshed.copy(deviceId = session.deviceId, deviceName = session.deviceName)
            FluxEnv.settings.saveAccount(merged)
            if (merged.hasDevice) {
                FluxEnv.settings.save(merged.proxyBaseUrl, merged.accessToken)
            }
            merged
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session
        }
    }

    private fun readable(error: Exception): String = when (error) {
        is CloudApiException -> error.message
        else -> "${error::class.simpleName}: ${error.message ?: "无详情"}"
    }
}
