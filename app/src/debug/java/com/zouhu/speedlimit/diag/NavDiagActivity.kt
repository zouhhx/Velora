package com.zouhu.speedlimit.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.zouhu.speedlimit.BuildConfig
import com.zouhu.speedlimit.data.log.CameraTrace
import com.zouhu.speedlimit.data.nav.NavTrace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only navigation crash report. Designed for a single-phone road test:
 * no ADB or laptop needed. After a crash, open Settings -> 导航诊断 / 导出日志,
 * then share the generated report.
 *
 * It collects:
 *  - Java uncaught crash (crash.log)
 *  - navigation lifecycle + 2s heartbeat (nav-trace.log)
 *  - Android historical process exit reasons (including native crash / ANR)
 *  - optional horizon-diag.log
 */
class NavDiagActivity : AppCompatActivity() {
    private lateinit var output: File
    private lateinit var diagDir: File
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = getExternalFilesDir("diag") ?: File(filesDir, "diag").apply { mkdirs() }
        diagDir = dir
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        val title = TextView(this).apply {
            text = "导航诊断报告"
            textSize = 18f
        }
        val share = Button(this).apply { text = "分享日志" }
        val shareCamera = Button(this).apply { text = "分享 camera trace" }
        text = TextView(this).apply { textSize = 10f }
        val scroll = ScrollView(this).apply { addView(text) }
        root.addView(title)
        root.addView(share)
        root.addView(shareCamera)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        output = File(dir, "nav-diag-$stamp.txt")
        writeReport()

        share.setOnClickListener { shareFile(output, "分享导航诊断日志") }
        shareCamera.setOnClickListener {
            val trace = CameraTrace.currentFile()
            if (trace == null || !trace.exists()) {
                text.text = "camera trace 不存在（仅 debug 构建、且已启动巡航后才会生成）\n\n${text.text}"
            } else {
                shareFile(trace, "分享 camera_list 采集")
            }
        }
    }

    private fun writeReport() {
        val report = buildReport(diagDir)
        runCatching { output.writeText(report) }
        text.text = report.take(30_000)
    }

    private fun buildReport(dir: File): String = buildString {
        appendLine("Velora navigation diagnostic")
        appendLine("time=${Date()}")
        appendLine("package=$packageName version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        appendLine("navi crashHandler armed=${NavTrace.armedReason}")
        appendLine()

        appendLine("===== historical process exits =====")
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val am = getSystemService(ActivityManager::class.java)
                val exits = am.getHistoricalProcessExitReasons(packageName, 0, 20)
                if (exits.isEmpty()) appendLine("(none)")
                exits.forEach { info ->
                    val whenText = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                        .format(Date(info.timestamp))
                    appendLine(
                        "time=$whenText reason=${reasonName(info.reason)} status=${info.status} " +
                            "importance=${info.importance} desc=${info.description ?: ""}",
                    )
                    runCatching {
                        info.traceInputStream?.use { input ->
                            val trace = input.readBytes().toString(Charsets.UTF_8)
                            if (trace.isNotBlank()) {
                                appendLine("--- trace ---")
                                appendLine(trace.take(40_000))
                            }
                        }
                    }.onFailure { appendLine("trace unavailable: ${it.javaClass.simpleName}") }
                    appendLine()
                }
            }.onFailure { appendLine("exit info unavailable: ${it.javaClass.simpleName}: ${it.message}") }
        } else {
            appendLine("(requires Android 11+)")
        }
        appendLine()

        appendLine("===== nav-trace.log (tail) =====")
        appendLine(readTail(File(dir, "nav-trace.log")))
        appendLine()
        appendLine("===== crash.log (tail) =====")
        appendLine(readTail(File(dir, "crash.log")))
        appendLine()
        appendLine("===== horizon-diag.log (tail) =====")
        appendLine(readTail(File(dir, "horizon-diag.log")))
        appendLine()
        appendLine("===== camera-trace.jsonl (tail, VA-C01) =====")
        appendLine(readTail(File(dir, "camera-trace.jsonl")))
    }

    private fun readTail(file: File): String {
        if (!file.exists()) return "(missing: ${file.name})"
        return runCatching { file.readText().takeLast(200_000) }
            .getOrElse { "(read failed: ${it.javaClass.simpleName})" }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        else -> "OTHER($reason)"
    }

    private fun shareFile(file: File, title: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.probe.fileprovider", file)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    title,
                ),
            )
        }.onFailure {
            text.text = "分享失败: ${it.javaClass.simpleName}: ${it.message}\n\n${text.text}"
        }
    }
}
