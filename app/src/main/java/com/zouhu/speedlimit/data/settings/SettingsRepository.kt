package com.zouhu.speedlimit.data.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.File

// Remove the old vendor credential before exposing any settings to consumers.
internal class GatewaySettingsMigration : DataMigration<Preferences> {
    private val legacyKey = stringPreferencesKey("ak")
    private val mode = stringPreferencesKey("mode")
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.contains(legacyKey) || currentData[mode] == "baidu" || currentData[mode] == "simulation"

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        remove(legacyKey)
        if (this[mode] == "baidu") this[mode] = "remote"
        if (this[mode] == "simulation") this[mode] = "direct"
    }
    override suspend fun cleanUp() = Unit
}

data class AppSettings(
    val thresholdPct: Int = 10,
    val keepScreenOn: Boolean = true,
    val soundEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val providerMode: String = "direct",
    val gatewayUrl: String = "",
    val gatewayToken: String = "",
    /** Baidu server AK: Horizon WebSocket, HTTP cruise and lamp. */
    val directAk: String = "",
    /** Optional server SK; current Baidu endpoints authenticate with AK only. */
    val serverSk: String = "",
    /** MiMo TTS bearer key. */
    val mimoKey: String = "",
    /** Android map/navi/search AK. Applied on the next app start. */
    val mapAk: String = "",
    val debugLog: Boolean = false,
    /**
     * Navigation map day/night: "day" | "night" | "system".
     *
     * Defaults to "day": the navi SDK otherwise flips the map to a dark 3D night scene
     * by wall-clock time, which the user cannot control and found surprising. "system"
     * follows the phone's dark-mode setting; nothing here is time based.
     */
    val dayNightMode: String = "day",
) {
    override fun toString(): String = "AppSettings(providerMode=$providerMode, credentials=redacted)"
}

class SettingsRepository(context: Context) {
    private val ds = PreferenceDataStoreFactory.create(migrations = listOf(GatewaySettingsMigration())) {
        context.preferencesDataStoreFile("slm_settings")
    }
    // Legacy no-backup DataStore. It is only a one-time migration source now;
    // new secrets live in SecretStore (EncryptedSharedPreferences).
    private val auth = PreferenceDataStoreFactory.create {
        File(context.noBackupFilesDir, "gateway_auth.preferences_pb")
    }
    private val secrets = SecretStore.get(context)

    private object Keys {
        val THRESHOLD = intPreferencesKey("threshold")
        val KEEP_ON = booleanPreferencesKey("keep_on")
        val SOUND = booleanPreferencesKey("sound")
        val TTS = booleanPreferencesKey("tts")
        val MODE = stringPreferencesKey("mode")
        val URL = stringPreferencesKey("gateway_url")
        val TOKEN = stringPreferencesKey("gateway_token")
        val LEGACY_DIRECT_AK = stringPreferencesKey("direct_ak")
        val LOG = booleanPreferencesKey("log")
        val DAY_NIGHT = stringPreferencesKey("map_day_night")
    }

    val settings: Flow<AppSettings> = combine(ds.data, auth.data, secrets.state) { p, credentials, secret ->
        val legacyAk = credentials[Keys.LEGACY_DIRECT_AK]
        AppSettings(
            thresholdPct = p[Keys.THRESHOLD] ?: 10,
            keepScreenOn = p[Keys.KEEP_ON] ?: true,
            soundEnabled = p[Keys.SOUND] ?: true,
            ttsEnabled = p[Keys.TTS] ?: true,
            providerMode = if (p[Keys.MODE] == "remote") "remote" else "direct",
            gatewayUrl = p[Keys.URL] ?: "",
            gatewayToken = credentials[Keys.TOKEN] ?: "",
            // A saved encrypted value wins; otherwise keep an old install's AK for
            // migration, then fall back to the build-time default.
            directAk = secret.serverAk.ifBlank {
                legacyAk ?: com.zouhu.speedlimit.BuildConfig.HORIZON_AK
            },
            serverSk = secret.serverSk.ifBlank { com.zouhu.speedlimit.BuildConfig.HORIZON_SK },
            mimoKey = secret.mimoKey.ifBlank { com.zouhu.speedlimit.BuildConfig.MIMO_API_KEY },
            mapAk = secret.mapAk.ifBlank { com.zouhu.speedlimit.BuildConfig.BAIDU_MAP_AK },
            debugLog = p[Keys.LOG] ?: false,
            dayNightMode = p[Keys.DAY_NIGHT]?.takeIf { it in DAY_NIGHT_MODES } ?: "day",
        )
    }

    suspend fun setThreshold(v: Int) { ds.edit { it[Keys.THRESHOLD] = v } }
    suspend fun setKeepScreenOn(v: Boolean) { ds.edit { it[Keys.KEEP_ON] = v } }
    suspend fun setSoundEnabled(v: Boolean) { ds.edit { it[Keys.SOUND] = v } }
    suspend fun setTtsEnabled(v: Boolean) { ds.edit { it[Keys.TTS] = v } }
    suspend fun setProviderMode(v: String) {
        require(v == "remote" || v == "direct")
        ds.edit { it[Keys.MODE] = v }
    }
    suspend fun setGatewayUrl(v: String) { ds.edit { it[Keys.URL] = v } }
    suspend fun setGatewayToken(v: String) { auth.edit { it[Keys.TOKEN] = v } }

    suspend fun setDirectAk(v: String) {
        secrets.update { it.copy(serverAk = v.trim()) }
        auth.edit { it.remove(Keys.LEGACY_DIRECT_AK) }
    }

    suspend fun setServerSk(v: String) {
        secrets.update { it.copy(serverSk = v.trim()) }
    }

    suspend fun setMimoKey(v: String) {
        secrets.update { it.copy(mimoKey = v.trim()) }
    }

    suspend fun setMapAk(v: String) {
        secrets.update { it.copy(mapAk = v.trim()) }
    }

    /** Q-25: drop the saved server AK so the build's HORIZON_AK becomes effective. */
    suspend fun resetDirectAk() {
        secrets.update { it.copy(serverAk = "") }
        auth.edit { it.remove(Keys.LEGACY_DIRECT_AK) }
    }

    suspend fun resetServerSk() {
        secrets.update { it.copy(serverSk = "") }
    }

    suspend fun resetMimoKey() {
        secrets.update { it.copy(mimoKey = "") }
    }

    suspend fun resetMapAk() {
        secrets.update { it.copy(mapAk = "") }
    }

    /**
     * One-time migration from the old plaintext no-backup DataStore. Called by
     * AppGraph before it starts collecting settings; the flow keeps a fallback so
     * existing installs are not briefly left without a key.
     */
    suspend fun migrateLegacySecrets() {
        runCatching {
            val legacy = auth.data.first()
            val oldAk = legacy[Keys.LEGACY_DIRECT_AK]
            if (!oldAk.isNullOrBlank() && secrets.current().serverAk.isBlank()) {
                secrets.update { it.copy(serverAk = oldAk) }
            }
            auth.edit {
                it.remove(Keys.LEGACY_DIRECT_AK)
                it.remove(Keys.TOKEN)
            }
        }
    }

    /** Synchronous reads for SDK initialization and TTS, never logged. */
    fun currentMimoKey(): String = secrets.current().mimoKey.ifBlank {
        com.zouhu.speedlimit.BuildConfig.MIMO_API_KEY
    }

    fun currentMapAk(): String = secrets.current().mapAk.ifBlank {
        com.zouhu.speedlimit.BuildConfig.BAIDU_MAP_AK
    }

    suspend fun setDebugLog(v: Boolean) { ds.edit { it[Keys.LOG] = v } }
    suspend fun setDayNightMode(v: String) {
        require(v in DAY_NIGHT_MODES)
        ds.edit { it[Keys.DAY_NIGHT] = v }
    }

    private companion object {
        val DAY_NIGHT_MODES = setOf("day", "night", "system")
    }
}
