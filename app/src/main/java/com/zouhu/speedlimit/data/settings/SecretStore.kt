package com.zouhu.speedlimit.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Secrets that must never be committed, logged or placed in an unencrypted
 * preferences file:
 *
 *  - [serverAk]: Baidu server AK for Horizon WebSocket, HTTP cruise and lamp APIs.
 *  - [serverSk]: optional Baidu server SK. Current endpoints authenticate with AK
 *    only; the value is stored for a future signed-request flow.
 *  - [mimoKey]: Xiaomi MiMo TTS bearer token.
 *  - [mapAk]: Android application AK used by map/navi/search. Overrides the
 *    manifest/BuildConfig fallback after an app restart.
 *
 * Storage is EncryptedSharedPreferences backed by an Android Keystore master key.
 * If the keystore is unavailable on a broken device we fall back to app-private
 * preferences rather than crashing; the fallback is still excluded from backup.
 */
class SecretStore private constructor(context: Context) {
    private val prefs: SharedPreferences = createPreferences(context.applicationContext)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SecretConfig> = _state.asStateFlow()

    fun current(): SecretConfig = _state.value

    fun update(transform: (SecretConfig) -> SecretConfig) {
        val next = transform(_state.value)
        prefs.edit()
            .putString(KEY_SERVER_AK, next.serverAk)
            .putString(KEY_SERVER_SK, next.serverSk)
            .putString(KEY_MIMO_KEY, next.mimoKey)
            .putString(KEY_MAP_AK, next.mapAk)
            .apply()
        _state.value = next
    }

    private fun read(): SecretConfig = SecretConfig(
        serverAk = prefs.getString(KEY_SERVER_AK, "").orEmpty(),
        serverSk = prefs.getString(KEY_SERVER_SK, "").orEmpty(),
        mimoKey = prefs.getString(KEY_MIMO_KEY, "").orEmpty(),
        mapAk = prefs.getString(KEY_MAP_AK, "").orEmpty(),
    )

    companion object {
        const val TAG = "VeloraSecrets"
        const val FILE = "velora_secrets"
        const val FALLBACK_FILE = "velora_secrets_private"
        const val KEY_SERVER_AK = "server_ak"
        const val KEY_SERVER_SK = "server_sk"
        const val KEY_MIMO_KEY = "mimo_key"
        const val KEY_MAP_AK = "map_ak"

        @Volatile
        private var instance: SecretStore? = null

        fun get(context: Context): SecretStore =
            instance ?: synchronized(this) {
                instance ?: SecretStore(context.applicationContext).also { instance = it }
            }

        private fun createPreferences(context: Context): SharedPreferences =
            runCatching {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    FILE,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrElse { error ->
                // A failed keystore should not make the whole app unusable. This file
                // is still app-private; the warning is deliberately value-free.
                Log.e(TAG, "encrypted secret storage unavailable: ${error.javaClass.simpleName}")
                context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
            }
    }
}

data class SecretConfig(
    val serverAk: String = "",
    val serverSk: String = "",
    val mimoKey: String = "",
    val mapAk: String = "",
)
