package `in`.setu.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("setu_settings")

/** User-visible settings and small bits of state that must survive process death. */
class Settings(private val context: Context) {
    private val store get() = context.dataStore

    val courierEnabled: Flow<Boolean> = store.data.map { it[COURIER] ?: true }
    val relayUrl: Flow<String> = store.data.map { it[RELAY_URL] ?: DEFAULT_RELAY_URL }
    val activeSosIdHex: Flow<String?> = store.data.map { it[ACTIVE_SOS] }
    val activeSosSince: Flow<Long> = store.data.map { it[ACTIVE_SOS_SINCE] ?: 0L }
    val lastUploadAt: Flow<Long> = store.data.map { it[LAST_UPLOAD] ?: 0L }
    val batteryPromptShown: Flow<Boolean> = store.data.map { it[BATTERY_PROMPT] ?: false }

    suspend fun setCourierEnabled(on: Boolean) = store.edit { it[COURIER] = on }
    suspend fun setRelayUrl(url: String) = store.edit { it[RELAY_URL] = url.trim().trimEnd('/') }
    suspend fun setBatteryPromptShown() = store.edit { it[BATTERY_PROMPT] = true }
    suspend fun setLastUploadAt(epochSeconds: Long) = store.edit { it[LAST_UPLOAD] = epochSeconds }

    suspend fun setActiveSos(idHex: String?, since: Long) = store.edit {
        if (idHex == null) {
            it.remove(ACTIVE_SOS)
            it.remove(ACTIVE_SOS_SINCE)
        } else {
            it[ACTIVE_SOS] = idHex
            it[ACTIVE_SOS_SINCE] = since
        }
    }

    suspend fun snapshot(): Snapshot = store.data.first().let {
        Snapshot(
            courierEnabled = it[COURIER] ?: true,
            relayUrl = it[RELAY_URL] ?: DEFAULT_RELAY_URL,
            activeSosIdHex = it[ACTIVE_SOS],
            activeSosSince = it[ACTIVE_SOS_SINCE] ?: 0L,
        )
    }

    data class Snapshot(
        val courierEnabled: Boolean,
        val relayUrl: String,
        val activeSosIdHex: String?,
        val activeSosSince: Long,
    )

    companion object {
        const val DEFAULT_RELAY_URL = "https://setu-relay.workers.dev"
        private val COURIER = booleanPreferencesKey("courier")
        private val RELAY_URL = stringPreferencesKey("relay_url")
        private val ACTIVE_SOS = stringPreferencesKey("active_sos")
        private val ACTIVE_SOS_SINCE = longPreferencesKey("active_sos_since")
        private val LAST_UPLOAD = longPreferencesKey("last_upload")
        private val BATTERY_PROMPT = booleanPreferencesKey("battery_prompt")
    }
}
