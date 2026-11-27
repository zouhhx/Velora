package com.zouhu.speedlimit.data.audio

import android.media.AudioManager
import android.media.ToneGenerator

class BeepPlayer {
    private var tone: ToneGenerator? = null

    fun beep() {
        val generator = tone ?: runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        }.getOrNull()?.also { tone = it } ?: return
        runCatching { generator.startTone(ToneGenerator.TONE_PROP_BEEP2, 180) }
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
    }
}
