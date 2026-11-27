package com.zouhu.speedlimit.data.tts

/**
 * The speech sink used by the navigation voice path. Implemented by [TtsPlayer] in
 * production and by fakes in tests; kept separate so the voice trigger can live outside
 * the Activity (VA-F03).
 */
interface NavSpeaker {
    suspend fun speak(text: String, enabled: Boolean)
    fun stop()
}
