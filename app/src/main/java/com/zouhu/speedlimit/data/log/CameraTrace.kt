package com.zouhu.speedlimit.data.log

import android.content.Context
import android.os.Build
import com.zouhu.speedlimit.BuildConfig
import com.zouhu.speedlimit.data.map.baidu.CruiseCallTrace
import com.zouhu.speedlimit.data.map.baidu.CruiseTraceSink
import com.zouhu.speedlimit.domain.model.GpsSample
import java.io.File

/**
 * Debug-only capture for VA-C01 (`camera_list` forward/behind semantics).
 *
 * Why this exists: the audit could prove that the app treats the nearest camera as
 * "ahead" using only an unsigned haversine distance, but it could not prove how often a
 * passed camera stays selected, nor what the server already filtered. Deciding a forward
 * filter without that evidence would risk silently dropping a real speed camera.
 *
 * The recorder therefore writes two line kinds to `diag/camera-trace.jsonl`:
 * - one line per HTTP response (request context + parsed list + raw body);
 * - one line per second of GPS, so the local per-frame recomputation can be replayed.
 *
 * Safety rules:
 * - release builds never initialise it;
 * - the request URL (which carries the AK) is never passed in;
 * - writes are best-effort and can never affect the cruise request path;
 * - the file rotates at 8 MB, keeping the previous file as `.1`.
 */
internal object CameraTrace : CruiseTraceSink {
    private const val FILE_NAME = "camera-trace.jsonl"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val GPS_MIN_INTERVAL_MS = 1_000L

    private val lock = Any()

    @Volatile
    private var enabled = false
    private var file: File? = null

    @Volatile
    private var lastGpsWriteMs = 0L

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            if (file != null) return
            val dir = context.getExternalFilesDir("diag") ?: File(context.filesDir, "diag")
            runCatching { dir.mkdirs() }
            val target = File(dir, FILE_NAME)
            file = target
            enabled = true
            appendLocked(
                target,
                CameraTraceJson.metaLine(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    android = "Android ${Build.VERSION.RELEASE}",
                    sdk = Build.VERSION.SDK_INT,
                    nowMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun currentFile(): File? = file

    fun isEnabled(): Boolean = enabled

    fun clear() {
        synchronized(lock) {
            runCatching { file?.delete() }
            lastGpsWriteMs = 0L
        }
    }

    override fun onHttp(trace: CruiseCallTrace) {
        if (!enabled) return
        runCatching { write(CameraTraceJson.httpLine(trace, System.currentTimeMillis())) }
    }

    override fun onGps(sample: GpsSample) {
        if (!enabled || !sample.hasFix) return
        val now = System.currentTimeMillis()
        if (now - lastGpsWriteMs < GPS_MIN_INTERVAL_MS) return
        lastGpsWriteMs = now
        runCatching { write(CameraTraceJson.gpsLine(sample, now)) }
    }

    private fun write(line: String) {
        synchronized(lock) {
            val target = file ?: return
            if (target.length() > MAX_BYTES) rotate(target)
            appendLocked(target, line)
        }
    }

    private fun rotate(target: File) {
        val old = File(target.parentFile, "$FILE_NAME.1")
        runCatching { old.delete() }
        runCatching { target.renameTo(old) }
    }

    private fun appendLocked(target: File, line: String) {
        runCatching { target.appendText(line + "\n") }
    }
}
