package top.qiuli55.flux.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.CloudApi
import top.qiuli55.flux.mobile.data.CloudApiException
import top.qiuli55.flux.mobile.data.FluxEnv
import top.qiuli55.flux.mobile.data.Pkce

data class LoginUiState(
    val identifier: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

class LoginViewModel : ViewModel() {

    private val cloud = CloudApi()
    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()

    fun onIdentifierChange(value: String) {
        _state.value = _state.value.copy(identifier = value, error = null)
    }

    fun onPasswordChange(value: String) {
        _state.value = _state.value.copy(password = value, error = null)
    }

    fun githubUrl(): String {
        val verifier = Pkce.newVerifier()
        FluxEnv.settings.saveOauthVerifier(verifier)
        return cloud.oauthStartUrl("github", codeChallenge = Pkce.challenge(verifier))
    }

    fun login(onSignedIn: (AccountSession) -> Unit) {
        val current = _state.value
        if (current.identifier.isBlank() || current.password.isBlank()) {
            _state.value = current.copy(error = "请输入账号和密码")
            return
        }
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val session = cloud.login(current.identifier, current.password)
                FluxEnv.settings.saveAccount(session)
                _state.value = _state.value.copy(busy = false)
                onSignedIn(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = readable(e))
            }
        }
    }

    /** 浏览器 OAuth 回跳带回来的一次性登录码。 */
    fun exchangeCode(code: String, onSignedIn: (AccountSession) -> Unit) {
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val session = cloud.exchange(code, FluxEnv.settings.readOauthVerifier())
                FluxEnv.settings.clearOauthVerifier()
                FluxEnv.settings.saveAccount(session)
                _state.value = _state.value.copy(busy = false)
                onSignedIn(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = readable(e))
            }
        }
    }

    private fun readable(error: Exception): String = when (error) {
        is CloudApiException -> error.message
        is IllegalArgumentException -> error.message ?: "输入不合法"
        else -> error.message ?: "网络异常，请稍后重试"
    }
}
