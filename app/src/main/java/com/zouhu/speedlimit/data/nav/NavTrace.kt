package com.zouhu.speedlimit.data.nav

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.zouhu.speedlimit.BuildConfig
import com.zouhu.speedlimit.data.settings.SecretRedaction
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only file trace for navigation lifecycle transitions.
 *
 * Why [arm] exists: Baidu Map-Navi installs its OWN `Thread.UncaughtExceptionHandler`
 * when the navi engine initialises (`com.baidu.navisdk.n`). That handler swallows the
 * Java stack — it uploads a statistic, then calls `System.exit(10)` and launches HOME.
 * A real-device crash therefore leaves no `crash.log`, no `[CRASH]` line, and shows up
 * in `ApplicationExitInfo` as `EXIT_SELF status=10` (exit code 10), which is exactly
 * what the 2026-11-15 report shows. So every time we hand control to the navi SDK we
 * re-install our handler on top of theirs and chain to it, keeping SDK behaviour intact
 * while finally recording the stack.
 */
internal object NavTrace {
    private val lock = Any()
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var dir: File? = null

    @Volatile
    private var file: File? = null

    /** Last [arm] reason; surfaced in the diagnostic report so we know the handler survived. */
    @Volatile
    var armedReason: String = "none"
        private set

    fun init(context: Context) {
        if (file == null) {
            synchronized(lock) {
                if (file == null) {
                    val d = context.getExternalFilesDir("diag") ?: File(context.filesDir, "diag")
                    d.mkdirs()
                    dir = d
                    file = File(d, "nav-trace.log")
                    log(
                        "APP",
                        "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                            "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} " +
                            "sdk=${Build.VERSION.SDK_INT}",
                    )
                }
            }
        }
        arm("init")
    }

    /**
     * Re-install our uncaught handler on top of whatever is currently default.
     * Idempotent and cheap when ours is already in place. Must be called after every
     * SDK entry point that may replace the default handler.
     */
    fun arm(reason: String) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashHandler) {
            armedReason = reason
            return
        }
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(current))
        armedReason = reason
        log("ARM", "reason=$reason previous=${current?.javaClass?.name ?: "none"}")
    }

    fun log(tag: String, message: String) {
        val line = "${clock.format(Date())} [$tag] ${SecretRedaction.redact(message)}"
        Log.i("VeloraNavTrace", line)
        synchronized(lock) {
            runCatching { file?.appendText(line + "\n") }
        }
    }

    /** Durable write: a native kill or `System.exit` right after must not lose the stack. */
    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val stamp = clock.format(Date())
        log("CRASH", "thread=${thread.name} armedAt=$armedReason ${throwable.javaClass.name}: ${throwable.message}")
        val d = dir ?: return
        val block = buildString {
            appendLine("==== $stamp thread=${thread.name} armedAt=$armedReason")
            appendLine(SecretRedaction.redact(throwable.stackTraceToString()))
            appendLine()
        }
        runCatching {
            FileOutputStream(File(d, "crash.log"), true).use { out ->
                out.write(block.toByteArray())
                out.flush()
                out.fd.sync()
            }
        }.onFailure { Log.w("VeloraNavTrace", "crash write failed: ${it.message}") }
    }

    private class CrashHandler(
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            runCatching { writeCrash(thread, throwable) }
            val prev = previous
            if (prev != null) {
                // Preserve the SDK's own post-crash behaviour (statistic, exit, HOME intent).
                prev.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }
}
