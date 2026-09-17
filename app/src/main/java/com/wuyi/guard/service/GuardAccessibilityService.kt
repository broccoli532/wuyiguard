package com.wuyi.guard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wuyi.guard.AppConfig
import com.wuyi.guard.LogText
import com.wuyi.guard.util.Logger
import com.wuyi.guard.util.Prefs

/**
 * 无障碍服务。
 *
 * 承担三件事：
 *  1. 回系统主屏幕：performGlobalAction(GLOBAL_ACTION_HOME)
 *  2. 清理后台：打开最近任务 → 找「清除全部」类按钮 → 点击 → 回主屏
 *  3. 节点取词：从窗口树里抽取文字，作为 OCR 的补充 / 无 GMS 时的降级方案
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GuardAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** 回主屏幕 */
        fun goHome(): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        /** 打开最近任务+点清除，结果通过回调返回 */
        fun clearBackground(callback: ((Boolean) -> Unit)? = null) {
            val s = instance
            if (s == null) {
                Logger.w("A11y", LogText.A11Y_NOT_CONNECTED)
                callback?.invoke(false)
                return
            }
            s.doClearBackground(callback)
        }

        /** 屏幕尺寸（给坐标兜底用） */
        fun screenSize(): Pair<Int, Int> {
            val s = instance ?: return Pair(0, 0)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val wm = s.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    val b = wm?.currentWindowMetrics?.bounds
                    if (b != null) Pair(b.width(), b.height()) else legacySize(s)
                } else {
                    legacySize(s)
                }
            } catch (e: Exception) {
                legacySize(s)
            }
        }

        @Suppress("DEPRECATION")
        private fun legacySize(s: GuardAccessibilityService): Pair<Int, Int> {
            val p = android.graphics.Point()
            try {
                val wm = s.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.defaultDisplay?.getRealSize(p)
            } catch (_: Exception) {
            }
            return if (p.x > 0) Pair(p.x, p.y) else Pair(0, 0)
        }

        /**
         * 导出当前窗口的节点树（诊断用）。
         * 打开最近任务界面后调用，就能看到清理按钮真实的
         * viewId / 内容描述 / 坐标，用于精确适配各种 ROM。
         */
        fun dumpWindowTree(): String {
            val s = instance ?: return "（无障碍服务未连接，无法导出）"
            return s.collectTreeText()
        }

        /** 从当前窗口树抽取文字（OCR 降级方案） */
        fun dumpWindowText(): String {
            return instance?.collectText() ?: ""
        }

        /**
         * 无障碍截图（Android 14 / API 34+）。
         * 这条通道**不走 MediaProjection**，所以不会被系统录屏/第三方录屏抢占，
         * 也不需要授权弹窗。返回 null 表示失败（ROM 不支持或无障碍未连接）。
         *
         * 注意：Android 14 起回调签名从 onSuccess(Bitmap) 改成了 onSuccess(ScreenshotResult)，
         * 在 compileSdk 35 下只能实现新版，因此这条通道的实际门槛是 Android 14+，
         * Android 11~13 仍走 MediaProjection（否则运行时会找不到回调方法）。
         */
        fun takeScreenshotAsync(
            executor: java.util.concurrent.Executor,
            callback: (android.graphics.Bitmap?) -> Unit
        ) {
            val s = instance
            if (s == null) {
                callback(null)
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                callback(null)
                return
            }
            try {
                s.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            // Android 14 起不再直接给 Bitmap，只给 HardwareBuffer，需要自己转换
                            var bmp: android.graphics.Bitmap? = null
                            try {
                                val buffer = screenshot.hardwareBuffer
                                if (buffer != null) {
                                    val wrapped = android.graphics.Bitmap.wrapHardwareBuffer(
                                        buffer, screenshot.colorSpace
                                    )
                                    if (wrapped != null) {
                                        // 必须复制一份：硬件缓冲区关闭后原图就失效了
                                        bmp = wrapped.copy(
                                            android.graphics.Bitmap.Config.ARGB_8888, false
                                        )
                                    }
                                    buffer.close()
                                } else {
                                    Logger.w("A11y", LogText.A11Y_SHOT_EMPTY)
                                }
                            } catch (e: Exception) {
                                Logger.e("A11y", LogText.A11Y_SHOT_CONVERT_FAIL, e)
                            }
                            callback(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            Logger.w("A11y", LogText.fmt(LogText.A11Y_SHOT_FAIL, "code" to errorCode))
                            callback(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Logger.e("A11y", LogText.A11Y_SHOT_EXCEPTION, e)
                callback(null)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logger.i("A11y", LogText.A11Y_CONNECTED)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val type = e.eventType
        // 折叠悬浮窗的兜底触发（主路径是 FloatingWindowService 的 ACTION_OUTSIDE，
        // 这里处理 ACTION_OUTSIDE 没派发到的情况）
        val isTouchStart = type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
        val isClick = type == AccessibilityEvent.TYPE_VIEW_CLICKED
        val isWindowChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isTouchStart && !isClick && !isWindowChange) return
        // 总开关关掉后，不再自动折叠（AppConfig.FLOAT_AUTO_COLLAPSE）
        if (!AppConfig.FLOAT_AUTO_COLLAPSE) return
        // 自己的界面（主界面、悬浮窗）不触发
        val pkg = e.packageName?.toString()
        if (pkg != null && pkg == packageName) return
        FloatingWindowService.collapseIfExpanded()
    }

    override fun onInterrupt() {
        Logger.w("A11y", LogText.A11Y_INTERRUPTED)
    }

    override fun onDestroy() {
        instance = null
        Logger.w("A11y", LogText.A11Y_DESTROYED)
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Logger.w("A11y", LogText.A11Y_UNBOUND)
        return super.onUnbind(intent)
    }

    /* ------------------------------------------------------------------ */
    /*  清理后台                                                            */
    /* ------------------------------------------------------------------ */

    private fun doClearBackground(callback: ((Boolean) -> Unit)?) {
        Logger.i("A11y", LogText.A11Y_CLEAR_START)
        val opened = performGlobalAction(GLOBAL_ACTION_RECENTS)
        if (!opened) {
            Logger.w("A11y", LogText.fmt(LogText.A11Y_RECENTS_RETRY, "retry" to AppConfig.TASK_RECENTS_RETRY_MS))
            mainHandler.postDelayed({
                val ok = performGlobalAction(GLOBAL_ACTION_RECENTS)
                if (!ok) {
                    Logger.e("A11y", LogText.A11Y_RECENTS_FAIL, null)
                    finishClear(false, callback)
                } else {
                    Logger.i("A11y", LogText.A11Y_RECENTS_RETRY_OK)
                    pollClearButton(0, callback)
                }
            }, AppConfig.TASK_RECENTS_RETRY_MS)
            return
        }
        // 已开启备用清理：说明本来就识别不到清除按钮，
        // 再做一轮关键词查找只是白白增加延迟，直接走坐标点击
        if (com.wuyi.guard.util.Prefs.isClearFallback(this)) {
            Logger.i("A11y", "已开启备用清理：跳过清除按钮文字查找，直接坐标点击")
            mainHandler.postDelayed({
                val tapped = tapClearFallback()
                mainHandler.postDelayed({ finishClear(tapped, callback) }, 600)
            }, AppConfig.TASK_WAIT_RECENTS_MS)
            return
        }

        mainHandler.postDelayed({ pollClearButton(0, callback) }, AppConfig.TASK_WAIT_RECENTS_MS)
    }

    /**
     * 轮询找「清除全部」按钮。
     * 原因：最近任务界面的打开耗时在不同机器/不同负载下差别很大（刚退出游戏时尤其慢），
     * 固定等一次经常会扑空，导致清理步骤整个丢失。这里改成反复找，找到就点。
     */
    private fun pollClearButton(attempt: Int, callback: ((Boolean) -> Unit)?) {
        mainHandler.postDelayed({
            val hit = try {
                findAndClickClearAll()
            } catch (e: Exception) {
                Logger.e("A11y", LogText.A11Y_CLEAR_FIND_ERROR, e)
                false
            }
            when {
                hit -> {
                    Logger.i("A11y", LogText.fmt(LogText.A11Y_CLEAR_HIT, "n" to attempt + 1))
                    finishClear(true, callback)
                }
                attempt + 1 < AppConfig.TASK_CLEAR_MAX_ATTEMPTS -> {
                    pollClearButton(attempt + 1, callback)
                }
                else -> {
                    Logger.w(
                        "A11y",
                        LogText.fmt(
                            LogText.A11Y_CLEAR_GIVE_UP,
                            "n" to AppConfig.TASK_CLEAR_MAX_ATTEMPTS
                        )
                    )
                    if (com.wuyi.guard.util.Prefs.isClearFallback(this)) {
                        val tapped = tapClearFallback()
                        // 手势派发需要一点时间，等一下再回主屏
                        mainHandler.postDelayed({ finishClear(tapped, callback) }, 600)
                    } else {
                        finishClear(false, callback)
                    }
                }
            }
        }, AppConfig.TASK_CLEAR_POLL_INTERVAL_MS)
    }

    private fun finishClear(hit: Boolean, callback: ((Boolean) -> Unit)?) {
        mainHandler.postDelayed({
            if (AppConfig.TASK_GO_HOME) performGlobalAction(GLOBAL_ACTION_HOME)
            callback?.invoke(hit)
        }, if (hit) AppConfig.TASK_AFTER_CLEAR_MS else 200L)
    }

    /** 在当前窗口树里找「清除全部」类按钮并点击 */
    private fun findAndClickClearAll(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val target = searchClearNode(root)
            if (target != null) {
                val node = target
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked && node.parent != null) {
                    node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else clicked
            } else false
        } finally {
            root.recycle()
        }
    }

    private fun searchClearNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val combined = "$text $desc"
            if (combined.isNotBlank()) {
                for (kw in AppConfig.CLEAR_ALL_KEYWORDS) {
                    if (combined.contains(kw, ignoreCase = true)) {
                        // 优先返回可点击的节点，否则往上找一层
                        if (node.isClickable) return node
                        val p = node.parent
                        if (p != null && p.isClickable) return p
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    /* ------------------------------------------------------------------ */
    /*  节点树导出（诊断）                                                  */
    /* ------------------------------------------------------------------ */

    private var dumpCount = 0

    private fun collectTreeText(): String {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Logger.e("A11y", "读取窗口失败", e)
            null
        } ?: return "（拿不到当前窗口：无障碍未开启，或当前界面不允许读取）"

        val sb = StringBuilder()
        dumpCount = 0
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())
        sb.append("========== 窗口节点树 ==========\n")
        sb.append("时间：").append(time).append('\n')
        sb.append("包名：").append(root.packageName ?: "-").append('\n')
        sb.append("--------------------------------\n")
        try {
            dumpNode(root, 0, sb)
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
        sb.append("--------------------------------\n")
        sb.append("共导出 ").append(dumpCount).append(" 个节点\n")
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        if (depth > 24 || dumpCount >= AppConfig.DUMP_MAX_NODES) return
        dumpCount++
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        sb.append(indent).append('[').append(depth).append("] ").append(cls).append('\n')
        if (text.isNotBlank()) sb.append(indent).append("    text=\"").append(text).append("\"\n")
        if (desc.isNotBlank()) sb.append(indent).append("    desc=\"").append(desc).append("\"\n")
        if (id.isNotBlank()) sb.append(indent).append("    id=").append(id).append('\n')
        sb.append(indent)
            .append("    clickable=").append(node.isClickable)
            .append(" enabled=").append(node.isEnabled)
            .append(" bounds=").append(rect.toShortString())
            .append('\n')

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            dumpNode(child, depth + 1, sb)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  坐标兜底点击                                                        */
    /* ------------------------------------------------------------------ */

    /** 用无障碍手势点一下指定坐标（找不到清除按钮时的兜底） */
    private fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Logger.w("A11y", "系统版本过低，不支持手势点击")
            return false
        }
        return try {
            val path = android.graphics.Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            val ok = dispatchGesture(gesture, null, null)
            Logger.i("A11y", "坐标兜底点击 ($x, $y)：${if (ok) "已派发" else "派发失败"}")
            ok
        } catch (e: Exception) {
            Logger.e("A11y", "坐标兜底点击异常", e)
            false
        }
    }

    private fun tapClearFallback(): Boolean {
        val (sw, sh) = screenSize()
        if (sw <= 0 || sh <= 0) {
            Logger.w("A11y", "拿不到屏幕尺寸，跳过坐标兜底")
            return false
        }
        val x = sw * AppConfig.CLEAR_FALLBACK_X_PERCENT
        val y = sh * com.wuyi.guard.util.Prefs.fallbackYPercent(this)
        Logger.i("A11y", "启用坐标兜底：屏幕 ${sw}x${sh}，点击 ($x, $y)")
        return tapAt(x, y)
    }

    /* ------------------------------------------------------------------ */
    /*  节点取词                                                            */
    /* ------------------------------------------------------------------ */

    private fun collectText(): String {
        return try {
            val root = rootInActiveWindow ?: return ""
            val sb = StringBuilder()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 300) {
                val node = queue.removeFirst()
                node.text?.toString()?.let {
                    if (it.isNotBlank()) sb.append(it).append(' ')
                }
                node.contentDescription?.toString()?.let {
                    if (it.isNotBlank()) sb.append(it).append(' ')
                }
                count++
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            }
            root.recycle()
            sb.toString()
        } catch (e: Exception) {
            Logger.e("A11y", LogText.A11Y_NODE_TEXT_ERROR, e)
            ""
        }
    }
}
