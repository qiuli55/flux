package top.qiuli55.flux.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.fluxSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "flux_settings",
)

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val TOKEN = stringPreferencesKey("token")
    }

    val config: Flow<ConnectionConfig> = appContext.fluxSettingsStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { prefs ->
            ConnectionConfig(
                baseUrl = prefs[Keys.BASE_URL] ?: ConnectionConfig.DEFAULT_BASE_URL,
                token = prefs[Keys.TOKEN] ?: "",
            )
        }

    suspend fun save(baseUrl: String, token: String) {
        appContext.fluxSettingsStore.edit { prefs ->
            prefs[Keys.BASE_URL] = baseUrl.trim()
            prefs[Keys.TOKEN] = token
        }
    }
}
