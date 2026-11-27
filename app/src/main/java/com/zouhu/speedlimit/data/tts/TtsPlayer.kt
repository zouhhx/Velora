package com.zouhu.speedlimit.data.tts

import android.content.Context
import android.media.MediaPlayer
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import com.zouhu.speedlimit.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Navigation voice: phone talks directly to Xiaomi MiMo TTS (no gateway).
 * The key is supplied by the settings repository (encrypted local storage) and
 * falls back to the build-time value for a local checkout. An empty key simply
 * disables speech; it never triggers a request.
 */
class TtsPlayer(
    private val context: Context,
    private val apiKeyProvider: () -> String = { BuildConfig.MIMO_API_KEY },
) : NavSpeaker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
    private var player: MediaPlayer? = null
    private var lastText: String? = null
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val callLock = Any()

    @Volatile
    private var activeCall: Call? = null

    /** Incremented by [stop]; a response from an older navigation must not play. */
    @Volatile
    private var generation = 0

    override suspend fun speak(text: String, enabled: Boolean) {
        if (!enabled || text.isBlank() || text == lastText) return
        // Screen-off playback needs the CPU to finish the request and start audio.
        // MediaPlayer's wake mode then covers the playback tail (VA-F03).
        val wakeLock = powerManager
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Velora:nav-tts")
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire(30_000L) }
            }
        try {
            speakInternal(text)
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
    }

    private suspend fun speakInternal(text: String) {
        val gen = generation
        val bytes = withContext(Dispatchers.IO) {
            if (gen != generation) return@withContext null
            runCatching { directWav(text) }
                .onFailure { Log.w(TAG, "tts failed: ${it.javaClass.simpleName}") }
                .getOrNull()
        } ?: return
        // A navigation may have ended while the request was blocked in execute().
        if (gen != generation) return
        val file = File(context.cacheDir, "velora-tts.wav")
        withContext(Dispatchers.IO) { runCatching { file.writeBytes(bytes) } }
        withContext(Dispatchers.Main) {
            if (gen != generation) return@withContext
            val played = runCatching {
                player?.release()
                player = MediaPlayer().apply {
                    setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }
                true
            }.getOrElse {
                Log.w(TAG, "tts playback failed: ${it.message}")
                false
            }
            // Only a played instruction is deduped. A failed request must stay
            // retryable; recording it up-front swallowed every later attempt (VA-F11).
            if (played) lastText = text
        }
    }

    /**
     * Bound to navigation end (VA-N08): cancel the in-flight HTTP call, stop and release
     * the player, and let the next session speak the same first instruction again.
     * Coroutine cancellation alone does not interrupt OkHttp's blocking execute().
     */
    override fun stop() {
        generation++
        val call = synchronized(callLock) {
            activeCall.also { activeCall = null }
        }
        runCatching { call?.cancel() }
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        lastText = null
    }

    private fun directWav(text: String): ByteArray? {
        val key = apiKeyProvider().trim()
        if (key.isBlank() || text.length > 500) return null
        val body = JSONObject()
            .put("model", "mimo-v2.5-tts")
            .put(
                "messages",
                JSONArray()
                    // MiMo TTS: user = style instruction, assistant = text to speak.
                    .put(JSONObject().put("role", "user").put("content", "请用自然、清晰的导航播报语气。"))
                    .put(JSONObject().put("role", "assistant").put("content", text)),
            )
            .put("audio", JSONObject().put("format", "wav").put("voice", "mimo_default"))
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        val call = client.newCall(request)
        synchronized(callLock) { activeCall = call }
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "tts upstream status=${response.code}")
                    return null
                }
                val json = JSONObject(response.body?.string() ?: return null)
                val base64 = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optJSONObject("audio")
                    ?.optString("data")
                    .orEmpty()
                if (base64.isBlank()) return null
                Base64.decode(base64, Base64.DEFAULT)
                    .takeIf { it.isNotEmpty() && it.size <= 2_000_000 }
            }
        } finally {
            synchronized(callLock) {
                if (activeCall === call) activeCall = null
            }
        }
    }

    fun release() {
        stop()
    }

    private companion object {
        const val TAG = "VeloraTts"
        const val ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
