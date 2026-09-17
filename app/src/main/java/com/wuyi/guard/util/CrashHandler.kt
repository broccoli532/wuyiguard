package com.wuyi.guard.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获。
 *
 * 崩溃会同步写入：
 *   /sdcard/Android/data/com.wuyi.guard/files/wuyi_logs/crash_2026-09-14.txt
 *
 * 写入必须是同步 IO —— 进程马上就要死了，异步队列来不及落盘。
 * 出问题后把这份文件发出来，就能直接定位是哪一行崩的。
 */
object CrashHandler {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun install(context: Context) {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                save(context, thread, throwable)
            } catch (_: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun save(context: Context, thread: Thread, throwable: Throwable) {
        val sb = StringBuilder()
        val now = timeFmt.format(Date())
        sb.append("================ 崩溃记录 ================\n")
        sb.append("时间：$now\n")
        sb.append("线程：${thread.name} (id=${thread.id})\n")
        sb.append("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("版本：${context.packageManager.getPackageInfo(context.packageName, 0).versionName}\n")
        sb.append("异常：${throwable.javaClass.name}\n")
        sb.append("信息：${throwable.message}\n")
        sb.append("堆栈：\n")
        throwable.stackTrace.take(40).forEach { sb.append("    at $it\n") }
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 3) {
            sb.append("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
            cause.stackTrace.take(30).forEach { sb.append("    at $it\n") }
            cause = cause.cause
            depth++
        }
        sb.append("\n")

        val dir = Logger.getLogDir(context)
        val file = File(dir, "crash_${dayFmt.format(Date())}.txt")
        FileOutputStream(file, true).use { fos ->
            fos.write(sb.toString().toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
    }
}
