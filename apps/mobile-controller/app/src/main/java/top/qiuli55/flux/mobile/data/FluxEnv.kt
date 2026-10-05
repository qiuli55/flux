package top.qiuli55.flux.mobile.data

import android.content.Context

object FluxEnv {
    lateinit var settings: SettingsStore
        private set

    fun install(context: Context) {
        if (!::settings.isInitialized) {
            settings = SettingsStore(context.applicationContext)
        }
    }

    fun api(config: ConnectionConfig): FluxApi = FluxApi(config)
}
