package com.zouhu.speedlimit.probe

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class ProbeLog(val file: File) {
    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val start = SystemClock.elapsedRealtime()
    private val lock = Any()
    private val uiLines = ArrayDeque<String>()
    private var writer: OutputStreamWriter? = null
    private var view: TextView? = null
    private var scroll: ScrollView? = null

    init {
        runCatching {
            writer = OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)
        }
    }

    fun attach(view: TextView, scroll: ScrollView) {
        this.view = view
        this.scroll = scroll
    }

    fun log(tag: String, message: String) {
        val line = "${clockFormat.format(Date())} +${SystemClock.elapsedRealtime() - start}ms [$tag] $message"
        Log.i(TAG, line)
        synchronized(lock) {
            runCatching {
                writer?.apply {
                    write(line)
                    write("\n")
                    flush()
                }
            }
        }
        val target = view ?: return
        target.post {
            uiLines.addLast(line)
            while (uiLines.size > 500) uiLines.removeFirst()
            target.text = uiLines.joinToString("\n")
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun logThrowable(tag: String, throwable: Throwable) {
        val text = throwable.stackTraceToString()
        text.lineSequence().take(45).forEach { log(tag, "  $it") }
    }

    fun flush() {
        synchronized(lock) {
            runCatching {
                writer?.flush()
            }
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching {
                writer?.flush()
                writer?.close()
            }
            writer = null
        }
    }

    private companion object {
        const val TAG = "VeloraProbe"
    }
}
