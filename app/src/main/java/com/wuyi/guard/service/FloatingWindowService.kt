package com.wuyi.guard.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wuyi.guard.AppConfig
import com.wuyi.guard.LogText
import com.wuyi.guard.R
import com.wuyi.guard.databinding.LayoutFloatingWindowBinding
import com.wuyi.guard.util.Logger
import com.wuyi.guard.util.Prefs
import kotlin.math.abs

/**
 * 悬浮窗服务。
 *
 * 两个状态：
 *   - 展开：完整控制面板（两个开关 + 状态）
 *   - 折叠：一个小图标（APP logo）
 *
 * 折叠触发：无障碍服务检测到用户点击了悬浮窗以外的界面（GuardAccessibilityService 回调）。
 * 恢复：点击小图标。
 */
class FloatingWindowService : Service() {

    companion object {
        private const val NOTI_ID = 20240915
        private const val CHANNEL_ID = "wuyi_float"
        const val ACTION_SHOW = "action_show"
        const val ACTION_HIDE = "action_hide"

        @Volatile
        var isShowing: Boolean = false
            private set

        @Volatile
        private var instance: FloatingWindowService? = null

        fun show(context: Context) {
            val i = Intent(context, FloatingWindowService::class.java).setAction(ACTION_SHOW)
            ContextCompat.startForegroundService(context, i)
        }

        fun hide(context: Context) {
            context.startService(Intent(context, FloatingWindowService::class.java).setAction(ACTION_HIDE))
        }

        /** 无障碍服务回调：用户点了悬浮窗以外的界面 → 折叠成小图标 */
        fun collapseIfExpanded() {
            try {
                instance?.collapse()
            } catch (_: Exception) {
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var binding: LayoutFloatingWindowBinding? = null
    private var floatParams: WindowManager.LayoutParams? = null
    private var lastExpandMs = 0L
    private var appliedSizeDp = 0
    private val handler = Handler(Looper.getMainLooper())

    // ---------- 折叠小图标尺寸档位（56 / 48 两档，更多页切换） ----------

    /** @return 尺寸是否发生变化 */
    private fun applyCollapsedSize(): Boolean {
        val b = binding ?: return false
        val dp = Prefs.getCollapsedSizeDp(this)
        if (dp == appliedSizeDp) return false
        appliedSizeDp = dp

        val density = resources.displayMetrics.density
        val px = (dp * density + 0.5f).toInt()
        val logoPx = (px * 0.78f + 0.5f).toInt()

        b.collapsedIcon.layoutParams?.let { lp ->
            lp.width = px
            lp.height = px
            b.collapsedIcon.layoutParams = lp
        }
        b.imgLogo.layoutParams?.let { lp ->
            lp.width = logoPx
            lp.height = logoPx
            b.imgLogo.layoutParams = lp
        }
        Logger.i("Float", "折叠图标尺寸切换为 ${dp}dp")
        return true
    }

    // ---------- 限位：横竖屏 / 分辨率变化时拉回屏幕内 ----------

    private fun screenSize(): Pair<Int, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = windowManager?.currentWindowMetrics?.bounds
                if (b != null) Pair(b.width(), b.height()) else legacyScreenSize()
            } else {
                legacyScreenSize()
            }
        } catch (e: Exception) {
            legacyScreenSize()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyScreenSize(): Pair<Int, Int> {
        val p = android.graphics.Point()
        try {
            windowManager?.defaultDisplay?.getRealSize(p)
        } catch (_: Exception) {
        }
        return if (p.x > 0 && p.y > 0) {
            Pair(p.x, p.y)
        } else {
            Pair(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
    }

    /** 同步版：拖动过程中实时限制（此时 View 已测量完毕） */
    private fun clampNow() {
        if (!AppConfig.FLOAT_CLAMP_TO_SCREEN) return
        val p = floatParams ?: return
        val v = floatView ?: return
        val vw = v.width
        val vh = v.height
        if (vw <= 0 || vh <= 0) return
        val (sw, sh) = screenSize()
        val maxX = (sw - vw).coerceAtLeast(0)
        val maxY = (sh - vh).coerceAtLeast(0)
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(0, maxY)
    }

    /** 异步版：等布局完成再算（配置变化、尺寸档位切换时用） */
    private fun clampToScreen() {
        if (!AppConfig.FLOAT_CLAMP_TO_SCREEN) return
        val v = floatView ?: return
        v.post {
            val p = floatParams ?: return@post
            val (sw, sh) = screenSize()
            val maxX = (sw - v.width).coerceAtLeast(0)
            val maxY = (sh - v.height).coerceAtLeast(0)
            var changed = false
            if (p.x < 0) { p.x = 0; changed = true } else if (p.x > maxX) { p.x = maxX; changed = true }
            if (p.y < 0) { p.y = 0; changed = true } else if (p.y > maxY) { p.y = maxY; changed = true }
            if (changed) {
                Prefs.saveFloatPos(this, p.x, p.y)
                try {
                    windowManager?.updateViewLayout(v, p)
                } catch (_: Exception) {
                }
                Logger.i("Float", "悬浮窗超出屏幕，已拉回 ($p.x, $p.y)")
            }
        }
    }

    /** 横竖屏切换 / 屏幕尺寸变化时自动拉回 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Logger.d("Float", "屏幕配置变化（横竖屏/分辨率），重新限位")
        clampToScreen()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 14+ 必须带类型启动前台服务，否则直接抛 MissingForegroundServiceTypeException
        startForegroundCompat()
        if (!isShowing) showWindow()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notif = try {
            buildNotification()
        } catch (e: Exception) {
            Logger.e("Float", LogText.FLOAT_NOTIFY_FAIL, e)
            null
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTI_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTI_ID, notif)
            }
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务会被拒绝；这里兜底，至少不让进程崩
            Logger.e("Float", LogText.FLOAT_FG_FAIL, e)
            try {
                startForeground(NOTI_ID, notif)
            } catch (e2: Exception) {
                Logger.e("Float", LogText.FLOAT_FG_FALLBACK_FAIL, e2)
            }
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showWindow() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val b = LayoutFloatingWindowBinding.inflate(LayoutInflater.from(this))
        binding = b
        val view = b.root
        floatView = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val pos = Prefs.getFloatPos(this)

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (AppConfig.FLOAT_AUTO_COLLAPSE) {
            // FLAG_NOT_TOUCH_MODAL + FLAG_WATCH_OUTSIDE_TOUCH：
            // 触摸落在悬浮窗「外面」时，系统会单独派发一个 ACTION_OUTSIDE 给本窗口。
            // 关键是这个派发由系统的输入分发层完成，与下面点的是什么无关 ——
            // 点到游戏自绘画面、空白处、甚至没有任何控件的地方都会派发。
            // 只影响触摸，不影响截图 / OCR。
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pos.first
            y = pos.second
            alpha = AppConfig.FLOAT_ALPHA
        }
        floatParams = params

        // ★ 点击悬浮窗范围外 → 折叠。默认关闭（AppConfig.FLOAT_AUTO_COLLAPSE=false），
        // 关闭时完全不挂这个监听，窗口行为与改动前一致。
        // 只在 ACTION_OUTSIDE 时消费事件；其余事件返回 false 继续分发给子 View
        if (AppConfig.FLOAT_AUTO_COLLAPSE) {
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    collapse()
                    true
                } else {
                    false
                }
            }
        }

        // 拖动 + 点击（展开状态拖标题栏；折叠状态拖图标，单击展开，长按关闭）
        attachDrag(b.dragHandle)
        attachDrag(
            view = b.collapsedIcon,
            onTap = { expand() },
            onLongPress = {
                Toast.makeText(this, getString(R.string.toast_float_closed_by_longpress), Toast.LENGTH_SHORT).show()
                Logger.i("Float", LogText.FLOAT_LONG_PRESS_CLOSE)
                stopSelf()
            }
        )

        // ★ 标题栏按钮改成「缩小」：只折叠成小图标，不退出悬浮窗
        b.btnCollapseFloat.setOnClickListener {
            Logger.i("Float", LogText.FLOAT_CLICK_COLLAPSE)
            collapse()
        }

        // 两个开关
        b.switchNaYi.isChecked = Prefs.isNaYiEnabled(this)
        b.switchGuoTu.isChecked = Prefs.isGuoTuEnabled(this)

        b.switchNaYi.setOnCheckedChangeListener { _, checked ->
            resetAutoCollapse()
            Prefs.setNaYiEnabled(this, checked)
            ScreenCaptureService.processor?.naYiEnabled = checked
            Logger.i("Float", LogText.fmt(LogText.SWITCH_NA_YI_STATE, "state" to (if (checked) LogText.STATE_ON else LogText.STATE_OFF)))
            if (checked) ensureCapture()
        }
        b.switchGuoTu.setOnCheckedChangeListener { _, checked ->
            resetAutoCollapse()
            Prefs.setGuoTuEnabled(this, checked)
            ScreenCaptureService.processor?.guoTuEnabled = checked
            Logger.i("Float", LogText.fmt(LogText.SWITCH_GUO_TU_STATE, "state" to (if (checked) LogText.STATE_ON else LogText.STATE_OFF)))
            if (checked) ensureCapture()
        }

        // 立刻同步一次到采集服务
        ScreenCaptureService.processor?.let {
            it.naYiEnabled = Prefs.isNaYiEnabled(this)
            it.guoTuEnabled = Prefs.isGuoTuEnabled(this)
        }

        applyCollapsedSize()
        startStatusLoop()

        try {
            wm.addView(view, params)
            isShowing = true
            Logger.i("Float", LogText.FLOAT_SHOWN)
            clampToScreen()
        } catch (e: Exception) {
            Logger.e("Float", LogText.FLOAT_ADD_FAIL, e)
            Toast.makeText(this, getString(R.string.toast_overlay_permission_denied), Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    /**
     * 通用拖动：move 超过 4px 算拖动，否则算点击（回调 onTap）。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View, onTap: (() -> Unit)? = null, onLongPress: (() -> Unit)? = null) {
        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var moved = false
        var longPressed = false

        if (onLongPress != null) {
            view.setOnLongClickListener {
                longPressed = true
                onLongPress.invoke()
                true
            }
        }

        view.setOnTouchListener { _, event ->
            val params = floatParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetAutoCollapse()
                    startX = params.x
                    startY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    moved = false
                    longPressed = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    clampNow()
                    try {
                        windowManager?.updateViewLayout(floatView, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> when {
                    longPressed -> {
                        longPressed = false
                        true
                    }
                    moved -> {
                        clampNow()
                        Prefs.saveFloatPos(this, params.x, params.y)
                        true
                    }
                    else -> {
                        onTap?.invoke()
                        true
                    }
                }
                else -> false
            }
        }
    }

    /**
     * 折叠。三重触发来源（任一命中都会折叠）：
     *   1. ACTION_OUTSIDE：点击悬浮窗矩形范围外（主路径，不依赖任何界面元素）
     *   2. TYPE_TOUCH_INTERACTION_START：无障碍上报的"用户开始触摸屏幕"
     *   3. TYPE_VIEW_CLICKED / TYPE_WINDOW_STATE_CHANGED：点到控件 / 切换界面
     * 刚展开的 300ms 内忽略，避免"一点开就被折叠"。
     */
    fun collapse() {
        if (SystemClock.elapsedRealtime() - lastExpandMs < 300) return
        setCollapsed(true)
    }

    private fun expand() {
        lastExpandMs = SystemClock.elapsedRealtime()
        setCollapsed(false)
        scheduleAutoCollapse()
    }

    // ---------- 「无操作超时自动缩小」（纯定时器，不碰触摸，不干扰识别） ----------
    private val autoCollapseRunnable = Runnable { collapse() }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
        val delay = AppConfig.FLOAT_IDLE_COLLAPSE_MS
        if (delay > 0L) handler.postDelayed(autoCollapseRunnable, delay)
    }

    /** 用户有操作时重新计时 */
    private fun resetAutoCollapse() {
        val b = binding ?: return
        if (AppConfig.FLOAT_IDLE_COLLAPSE_MS > 0L && b.collapsedIcon.visibility != View.VISIBLE) {
            scheduleAutoCollapse()
        }
    }

    private fun setCollapsed(v: Boolean) {
        val b = binding ?: return
        val nowCollapsed = b.collapsedIcon.visibility == View.VISIBLE
        if (nowCollapsed == v) return
        handler.removeCallbacks(autoCollapseRunnable)
        b.collapsedIcon.visibility = if (v) View.VISIBLE else View.GONE
        b.expandedPanel.visibility = if (v) View.GONE else View.VISIBLE
        try {
            floatView?.let { fv -> floatParams?.let { p -> windowManager?.updateViewLayout(fv, p) } }
        } catch (_: Exception) {
        }
        Logger.d("Float", if (v) LogText.FLOAT_COLLAPSED else LogText.FLOAT_EXPANDED)
    }

    /** 开关打开时，如果采集服务还没跑，提示用户先在主界面开启 */
    private fun ensureCapture() {
        if (!ScreenCaptureService.isRunning) {
            Logger.w("Float", LogText.FLOAT_NEED_MONITOR)
            handler.post {
                Toast.makeText(this, getString(R.string.toast_need_monitor_for_switch), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val statusLoop = object : Runnable {
        override fun run() {
            val b = binding ?: return
            // 与主界面共享开关状态（以 Prefs 为准，双向同步）
            val naYi = Prefs.isNaYiEnabled(this@FloatingWindowService)
            val guoTu = Prefs.isGuoTuEnabled(this@FloatingWindowService)
            if (b.switchNaYi.isChecked != naYi) b.switchNaYi.isChecked = naYi
            if (b.switchGuoTu.isChecked != guoTu) b.switchGuoTu.isChecked = guoTu

            // 更多页切换了尺寸档位 → 立即生效
            if (applyCollapsedSize()) {
                try {
                    floatView?.let { fv -> floatParams?.let { pp -> windowManager?.updateViewLayout(fv, pp) } }
                } catch (_: Exception) {
                }
                clampToScreen()
            }

            val running = ScreenCaptureService.isRunning
            val active = (naYi || guoTu) && running
            // 显示当前 OCR 引擎：降级成「节点取词」时一眼就能看出识别不了游戏画面
            val engine = ScreenCaptureService.engineName
            val engineLabel = if (engine.contains("NodeText", ignoreCase = true)) {
                getString(R.string.float_status_ocr_node)
            } else {
                getString(R.string.float_status_ocr_mlkit)
            }
            b.tvStatus.text = when {
                !running -> getString(R.string.float_status_idle)
                active -> getString(R.string.float_status_running, AppConfig.TARGET_FPS) + " · " + engineLabel
                else -> getString(R.string.float_status_switch_off) + " · " + engineLabel
            }
            b.tvStatus.setTextColor(
                if (active) resources.getColor(R.color.state_ok, theme)
                else resources.getColor(R.color.state_warn, theme)
            )
            handler.postDelayed(this, 1000)
        }
    }

    private fun startStatusLoop() {
        handler.removeCallbacks(statusLoop)
        handler.post(statusLoop)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "无翼守护悬浮窗", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
        // ★ 通知小图标必须是位图（PNG），用 vector 会让 SystemUI 崩溃并带崩 App
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("无翼守护悬浮窗")
            .setContentText("点击可在通知栏停止")
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        isShowing = false
        instance = null
        handler.removeCallbacks(statusLoop)
        try {
            floatView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        floatView = null
        binding = null
        Logger.i("Float", LogText.FLOAT_REMOVED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
