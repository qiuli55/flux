package top.qiuli55.flux.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object FluxEnv {
    lateinit var settings: SettingsStore
        private set

    private val refreshMutex = Mutex()

    fun install(context: Context) {
        if (!::settings.isInitialized) {
            settings = SettingsStore(context.applicationContext)
        }
    }

    fun api(config: ConnectionConfig): FluxApi = FluxApi(config)

    /** 账号模式：baseUrl 指向云端设备中转（AccountSession.proxyBaseUrl）。 */
    private fun ConnectionConfig.isAccountMode(): Boolean = isRelay

    /**
     * 读取连接配置；账号模式下 access token 只有 30 分钟，已过期/即将过期时先续期。
     *
     * 之前只在启动时续期一次，长时间停留在应用里（如测试终端闭环）就会整页 401；
     * 每次取配置都检查一次，让任何一次页面操作都能自愈。
     */
    suspend fun configWithFreshToken(): ConnectionConfig {
        val config = settings.config.first()
        if (config.isAccountMode()) refreshAccountIfNeeded()
        return settings.config.first()
    }

    /** 续期账号令牌并写回（账号 + 当前连接）。无 refresh token 或失败返回 null。 */
    suspend fun refreshAccountIfNeeded(force: Boolean = false): AccountSession? = refreshMutex.withLock {
        val session = settings.account.first() ?: return@withLock null
        if (!session.isSignedIn || session.refreshToken.isBlank()) return@withLock null
        // expiresAtEpochSec = 0 表示旧版本没存有效期：不做主动续期，避免每次请求都打账号云。
        if (!force && (session.expiresAtEpochSec <= 0 || !session.isExpiringSoon)) return@withLock session
        val fresh = runCatching { CloudApi(session.cloudUrl).refresh(session.refreshToken) }.getOrNull()
            ?: return@withLock null
        val merged = fresh.copy(deviceId = session.deviceId, deviceName = session.deviceName)
        settings.saveAccount(merged)
        if (merged.hasDevice) settings.save(merged.proxyBaseUrl, merged.accessToken)
        merged
    }
}
