package com.wuyi.guard.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.wuyi.guard.AppConfig
import com.wuyi.guard.LogText
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 日志工具。
 *
 * 日志目录（免权限、全版本可用）：
 *      /sdcard/Android/data/com.wuyi.guard/files/wuyi_logs/log_2026-09-14.txt
 *
 * 触发事件额外单独写一份：
 *      /sdcard/Android/data/com.wuyi.guard/files/wuyi_logs/trigger_2026-09-14.txt
 *
 * 同时提供 exportToDownloads()，可把日志复制一份到「下载」目录方便取出。
 */
object Logger {

    private const val TAG = "WuYiGuard"
    private const val DIR_NAME = "wuyi_logs"
    private const val QUEUE_CAP = 512

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAP)
    private val running = AtomicBoolean(false)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        startWriter()
    }

    /** 日志目录 */
    fun getLogDir(context: Context? = null): File {
        val ctx = context?.applicationContext ?: appContext
        val base = ctx?.getExternalFilesDir(null)
            ?: Environment.getExternalStorageDirectory()
        val dir = File(base, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun startWriter() {
        if (running.getAndSet(true)) return
        Thread({
            while (true) {
                val line = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    break
                }
                if (AppConfig.LOG_TO_FILE) writeLine(line)
            }
        }, "wuyi-log-writer").apply { isDaemon = true }.start()
    }

    private fun writeLine(line: String) {
        try {
            val ctx = appContext ?: return
            val day = dayFmt.format(Date())
            val file = File(getLogDir(ctx), "log_$day.txt")
            FileOutputStream(file, true).use { fos ->
                fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                fos.flush()
            }
        } catch (e: Exception) {
            if (AppConfig.LOG_TO_LOGCAT) Log.e(TAG, "写日志失败: ${e.message}")
        }
    }

    private fun enqueue(level: String, tag: String, msg: String) {
        val ts = timeFmt.format(Date())
        val line = "$ts  $level/$tag  $msg"
        if (AppConfig.LOG_TO_LOGCAT) {
            when (level) {
                "E" -> Log.e("$TAG:$tag", msg)
                "W" -> Log.w("$TAG:$tag", msg)
                "D" -> Log.d("$TAG:$tag", msg)
                else -> Log.i("$TAG:$tag", msg)
            }
        }
        queue.offer(line)
    }

    @JvmStatic fun v(tag: String, msg: String) = enqueue("V", tag, msg)
    @JvmStatic fun d(tag: String, msg: String) = enqueue("D", tag, msg)
    @JvmStatic fun i(tag: String, msg: String) = enqueue("I", tag, msg)
    @JvmStatic fun w(tag: String, msg: String) = enqueue("W", tag, msg)
    @JvmStatic fun e(tag: String, msg: String, t: Throwable? = null) =
        enqueue("E", tag, msg + (t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    /** 触发事件单独记录 */
    @JvmStatic
    fun trigger(reason: String, detail: String = "") {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())
        val line = LogText.fmt(LogText.TRIGGER_LINE,
            "time" to ts, "reason" to reason, "detail" to detail)
        i("TRIGGER", line)
        if (AppConfig.LOG_TRIGGER_SEPARATE && AppConfig.LOG_TO_FILE) {
            try {
                val ctx = appContext ?: return
                val day = dayFmt.format(Date())
                val file = File(getLogDir(ctx), "trigger_$day.txt")
                FileOutputStream(file, true).use {
                    it.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 清理超过保留天数的日志 */
    fun purgeOldLogs() {
        try {
            val ctx = appContext ?: return
            val dir = getLogDir(ctx)
            val cutoff = System.currentTimeMillis() - AppConfig.LOG_KEEP_DAYS * 24L * 3600_000L
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {
        }
    }

    /** 读取最近若干行日志（给「更多」页显示用） */
    fun readRecentLines(maxLines: Int = 200): String {
        return try {
            val ctx = appContext ?: return ""
            val day = dayFmt.format(Date())
            val file = File(getLogDir(ctx), "log_$day.txt")
            if (!file.exists()) return "（暂无日志）"
            val lines = file.readLines(Charsets.UTF_8)
            lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    /** 写一份独立文件（用于导出节点树等），返回绝对路径；失败返回 null */
    fun writeExtra(context: Context?, fileName: String, content: String): String? {
        return try {
            val c = context?.applicationContext ?: appContext ?: return null
            val dir = getLogDir(c)
            val f = File(dir, fileName)
            FileOutputStream(f, false).use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            f.absolutePath
        } catch (e: Exception) {
            if (AppConfig.LOG_TO_LOGCAT) Log.e(TAG, "写文件失败: ${e.message}")
            null
        }
    }

    /** 读取今天的崩溃日志（没有就返回提示） */
    fun readCrash(): String {
        return try {
            val ctx = appContext ?: return ""
            val day = dayFmt.format(Date())
            val file = File(getLogDir(ctx), "crash_$day.txt")
            if (!file.exists()) return "（今天没有捕获到崩溃）"
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            "读取崩溃日志失败: ${e.message}"
        }
    }
}
