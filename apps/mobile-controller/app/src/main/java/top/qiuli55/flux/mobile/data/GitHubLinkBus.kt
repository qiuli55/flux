package top.qiuli55.flux.mobile.data

import kotlinx.coroutines.flow.MutableSharedFlow

/** `flux://github-link?code=&state=` 回跳内容：交给后端完成授权码换票。 */
data class GitHubLinkCode(val code: String, val state: String)

/**
 * GitHub 仓库权限授权回跳总线。
 *
 * 与云账号登录的 [CloudAuthBus]（`flux://auth`）分开：两条链路的 code 不能混用。
 */
object GitHubLinkBus {
    val events = MutableSharedFlow<GitHubLinkCode>(extraBufferCapacity = 4)

    fun publish(code: String, state: String) {
        if (code.isNotBlank() && state.isNotBlank()) {
            events.tryEmit(GitHubLinkCode(code, state))
        }
    }
}
