package top.qiuli55.flux.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.CloudApi
import top.qiuli55.flux.mobile.data.CloudApiException
import top.qiuli55.flux.mobile.data.CloudAuthApi
import top.qiuli55.flux.mobile.data.FluxEnv
import top.qiuli55.flux.mobile.data.Pkce

/** 登录页上的次级表单：注册 / 忘记密码。 */
enum class AccountAccessSheet { REGISTER, FORGOT }

data class RegisterFormState(
    val email: String = "",
    val username: String = "",
    val password: String = "",
    val code: String = "",
    val sendingCode: Boolean = false,
    val cooldownSec: Int = 0,
    val error: String? = null,
)

data class ForgotFormState(
    val email: String = "",
    val code: String = "",
    val newPassword: String = "",
    val sendingCode: Boolean = false,
    val cooldownSec: Int = 0,
    val submitting: Boolean = false,
    val error: String? = null,
)

data class LoginUiState(
    val identifier: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** 账号云是否允许发信；探测失败按不可用处理。 */
    val mailEnabled: Boolean = false,
    val mailChecked: Boolean = false,
    val sheet: AccountAccessSheet? = null,
    val register: RegisterFormState = RegisterFormState(),
    val forgot: ForgotFormState = ForgotFormState(),
    /** 登录页顶部的成功提示（如密码已重置）。 */
    val notice: String? = null,
)

class LoginViewModel(
    private val cloud: CloudAuthApi = CloudApi(),
    private val saveAccount: suspend (AccountSession) -> Unit = { FluxEnv.settings.saveAccount(it) },
) : ViewModel() {

    /** `viewModel()` 通过反射创建时用这个无参构造。 */
    constructor() : this(CloudApi())

    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()

    private var registerCooldownJob: Job? = null
    private var forgotCooldownJob: Job? = null

    init {
        probeMailStatus()
    }

    // ------------------------------------------------------------ 登录

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
        if (current.busy) return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val session = cloud.login(current.identifier, current.password)
                saveAccount(session)
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
                saveAccount(session)
                _state.value = _state.value.copy(busy = false)
                onSignedIn(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = readable(e))
            }
        }
    }

    // ------------------------------------------------------------ 发信探测

    private fun probeMailStatus() {
        viewModelScope.launch {
            val enabled = try {
                cloud.mailStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _state.value = _state.value.copy(mailEnabled = enabled, mailChecked = true)
        }
    }

    // ------------------------------------------------------------ 注册

    fun openRegister() {
        val email = _state.value.identifier.trim().takeIf { isEmail(it) }.orEmpty()
        _state.value = _state.value.copy(
            sheet = AccountAccessSheet.REGISTER,
            notice = null,
            register = RegisterFormState(email = email),
        )
    }

    fun dismissAccessSheet() {
        registerCooldownJob?.cancel()
        forgotCooldownJob?.cancel()
        _state.value = _state.value.copy(
            sheet = null,
            register = RegisterFormState(),
            forgot = ForgotFormState(),
        )
    }

    fun onRegisterEmailChange(value: String) {
        updateRegister { it.copy(email = value, error = null) }
    }

    fun onRegisterUsernameChange(value: String) {
        updateRegister { it.copy(username = value, error = null) }
    }

    fun onRegisterPasswordChange(value: String) {
        updateRegister { it.copy(password = value, error = null) }
    }

    fun onRegisterCodeChange(value: String) {
        updateRegister { it.copy(code = value, error = null) }
    }

    fun sendRegisterCode() {
        val form = _state.value.register
        if (form.sendingCode || form.cooldownSec > 0 || _state.value.busy) return
        val email = form.email.trim()
        if (!isEmail(email)) {
            updateRegister { it.copy(error = "请输入正确的邮箱") }
            return
        }
        updateRegister { it.copy(sendingCode = true, error = null) }
        viewModelScope.launch {
            try {
                cloud.sendRegisterCode(email)
                updateRegister { it.copy(sendingCode = false, cooldownSec = CODE_COOLDOWN_SEC) }
                startCooldown(register = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateRegister { it.copy(sendingCode = false, error = readable(e)) }
            }
        }
    }

    /** 注册成功直接落会话并进入 App；邮件服务关闭时不要求验证码。 */
    fun submitRegister(onSignedIn: (AccountSession) -> Unit) {
        val current = _state.value
        if (current.busy) return
        val form = current.register
        val email = form.email.trim()
        val username = form.username.trim()
        val problem = when {
            !isEmail(email) -> "请输入正确的邮箱"
            username.isBlank() -> "请输入用户名"
            else -> validateNewPassword(form.password, email, username)
        }
        if (problem != null) {
            updateRegister { it.copy(error = problem) }
            return
        }
        if (current.mailEnabled && form.code.isBlank()) {
            updateRegister { it.copy(error = "请输入邮箱验证码") }
            return
        }
        _state.value = current.copy(busy = true, register = form.copy(error = null))
        viewModelScope.launch {
            try {
                val session = cloud.register(
                    email = email,
                    username = username,
                    password = form.password,
                    code = form.code.trim().takeIf { it.isNotBlank() },
                )
                saveAccount(session)
                registerCooldownJob?.cancel()
                _state.value = _state.value.copy(
                    busy = false,
                    sheet = null,
                    register = RegisterFormState(),
                )
                onSignedIn(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (_state.value.mailEnabled) {
                    readable(e)
                } else {
                    readable(e) + "（当前邮件服务未开启，稍后可再试）"
                }
                _state.value = _state.value.copy(
                    busy = false,
                    register = _state.value.register.copy(error = message),
                )
            }
        }
    }

    private fun startCooldown(register: Boolean) {
        if (register) registerCooldownJob?.cancel() else forgotCooldownJob?.cancel()
        val job = viewModelScope.launch {
            for (remaining in CODE_COOLDOWN_SEC - 1 downTo 0) {
                delay(1_000)
                if (register) {
                    updateRegister { it.copy(cooldownSec = remaining) }
                } else {
                    updateForgot { it.copy(cooldownSec = remaining) }
                }
            }
        }
        if (register) registerCooldownJob = job else forgotCooldownJob = job
    }

    // ------------------------------------------------------------ 忘记密码

    fun openForgot() {
        if (!_state.value.mailEnabled) return
        val email = _state.value.identifier.trim().takeIf { isEmail(it) }.orEmpty()
        _state.value = _state.value.copy(
            sheet = AccountAccessSheet.FORGOT,
            notice = null,
            forgot = ForgotFormState(email = email),
        )
    }

    fun onForgotEmailChange(value: String) {
        updateForgot { it.copy(email = value, error = null) }
    }

    fun onForgotCodeChange(value: String) {
        updateForgot { it.copy(code = value, error = null) }
    }

    fun onForgotPasswordChange(value: String) {
        updateForgot { it.copy(newPassword = value, error = null) }
    }

    fun sendForgotCode() {
        val form = _state.value.forgot
        if (form.sendingCode || form.cooldownSec > 0 || form.submitting) return
        val email = form.email.trim()
        if (!isEmail(email)) {
            updateForgot { it.copy(error = "请输入正确的邮箱") }
            return
        }
        updateForgot { it.copy(sendingCode = true, error = null) }
        viewModelScope.launch {
            try {
                cloud.forgotPassword(email)
                updateForgot { it.copy(sendingCode = false, cooldownSec = CODE_COOLDOWN_SEC) }
                startCooldown(register = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateForgot { it.copy(sendingCode = false, error = readable(e)) }
            }
        }
    }

    /** 重置成功不自动登录、不写会话，只回登录页给出中性提示。 */
    fun submitForgotReset() {
        val current = _state.value
        val form = current.forgot
        if (form.submitting) return
        val email = form.email.trim()
        val problem = when {
            !isEmail(email) -> "请输入正确的邮箱"
            form.code.isBlank() -> "请输入邮箱验证码"
            else -> validateNewPassword(form.newPassword, email, "")
        }
        if (problem != null) {
            updateForgot { it.copy(error = problem) }
            return
        }
        _state.value = current.copy(forgot = form.copy(submitting = true, error = null))
        viewModelScope.launch {
            try {
                cloud.resetPassword(email, form.code.trim(), form.newPassword)
                forgotCooldownJob?.cancel()
                _state.value = _state.value.copy(
                    sheet = null,
                    forgot = ForgotFormState(),
                    identifier = email.ifBlank { _state.value.identifier },
                    password = "",
                    notice = "密码已重置，请使用新密码登录",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    forgot = _state.value.forgot.copy(submitting = false, error = readable(e)),
                )
            }
        }
    }

    // ------------------------------------------------------------ 工具

    private fun updateRegister(block: (RegisterFormState) -> RegisterFormState) {
        _state.value = _state.value.copy(register = block(_state.value.register))
    }

    private fun updateForgot(block: (ForgotFormState) -> ForgotFormState) {
        _state.value = _state.value.copy(forgot = block(_state.value.forgot))
    }

    private fun readable(error: Exception): String = when (error) {
        is CloudApiException -> error.message
        is IllegalArgumentException -> error.message ?: "输入不合法"
        else -> error.message ?: "网络异常，请稍后重试"
    }

    companion object {
        const val CODE_COOLDOWN_SEC = 60
    }
}

/** 仅做前端预检，最终仍以账号云校验为准。 */
internal fun validateNewPassword(password: String, email: String, username: String): String? {
    if (password.length < 8) return "密码至少 8 位"
    if (password.none { it.isLetter() } || password.none { it.isDigit() }) {
        return "密码需要同时包含字母和数字"
    }
    val lower = password.lowercase()
    val emailLower = email.trim().lowercase()
    val emailLocal = emailLower.substringBefore("@")
    if (emailLower.isNotBlank() && lower == emailLower) return "密码不能和邮箱相同"
    if (emailLocal.isNotBlank() && lower == emailLocal) return "密码不能和邮箱前缀相同"
    if (username.isNotBlank() && lower == username.trim().lowercase()) return "密码不能和用户名相同"
    return null
}

internal fun isEmail(value: String): Boolean {
    val trimmed = value.trim()
    val at = trimmed.indexOf('@')
    return at > 0 && at < trimmed.length - 1 && !trimmed.any { it.isWhitespace() }
}
