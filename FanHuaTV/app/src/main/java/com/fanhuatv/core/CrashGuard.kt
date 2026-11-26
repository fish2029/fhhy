package com.fanhuatv.core

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局兜底：未捕获异常不再直接闪退。
 * - 崩溃堆栈写入 filesDir/logs/crash_log.txt（上限 256KB，超出清空重写）；
 * - 弹一句提示并重启主界面，尽量保住会话；
 * - 10 秒内连续崩溃 3 次视为启动死循环，交回系统默认处理。
 */
object CrashGuard {

    private const val MAX_LOG_BYTES = 256 * 1024
    private var lastCrashAt = 0L
    private var crashCount = 0

    /** 最近一次崩溃的一句话摘要：首页横幅直接展示，截图即可反馈，不用翻日志 */
    var lastCrashSummary: String = ""
        private set

    fun install(app: android.app.Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val now = System.currentTimeMillis()
            crashCount = if (now - lastCrashAt < 10_000) crashCount + 1 else 1
            lastCrashAt = now
            writeLog(app, thread, throwable)
            if (crashCount >= 3) {
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            try {
                val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                val show = {
                    try {
                        Toast.makeText(app, "出了点小问题，已自动恢复", Toast.LENGTH_SHORT).show()
                        if (intent != null) app.startActivity(intent)
                    } catch (_: Exception) {
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) show()
                else Handler(Looper.getMainLooper()).post(show)
            } catch (_: Exception) {
            }
        }
    }

    private fun writeLog(app: android.app.Application, thread: Thread, throwable: Throwable) {
        try {
            val dir = File(app.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, "crash_log.txt")
            if (f.length() > MAX_LOG_BYTES) f.delete()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            f.appendText(
                "─────\n时间: $time\n线程: ${thread.name}\n" +
                        throwable.stackTraceToString() + "\n"
            )
            // 一句话摘要：异常类 + 消息前 80 字 + 首个堆栈帧，供首页横幅展示
            val frame = throwable.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "?"
            val msg = (throwable.message ?: "").take(80)
            val summary = "${throwable.javaClass.simpleName}: $msg @$frame"
            lastCrashSummary = summary
            File(dir, "last_crash.txt").writeText("$time | $summary\n")
        } catch (_: Exception) {
        }
    }
}
