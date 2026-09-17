package com.wuyi.guard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wuyi.guard.AppConfig
import com.wuyi.guard.LogText
import com.wuyi.guard.R
import com.wuyi.guard.engine.FrameProcessor
import com.wuyi.guard.engine.OcrEngine
import com.wuyi.guard.engine.OcrEngineRouter
import com.wuyi.guard.engine.TaskExecutor
import com.wuyi.guard.util.Logger
import com.wuyi.guard.util.Prefs
import kotlin.math.max

/**
 * 屏幕采集 + 分析服务。
 *
 * Android 9 上第三方 App 截屏只有一条合法路：MediaProjection（屏幕录制）。
 * AccessibilityService#takeScreenshot 是 Android 11（API 30）才加的，所以用不了。
 *
 * 流程：
 *   MediaProjection -> VirtualDisplay -> ImageReader
 *   每帧：算平均亮度（判黑白） + 按目标帧率抽帧送 OCR
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val NOTI_ID = 20240914
        private const val CHANNEL_ID = "wuyi_capture"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val ACTION_STOP = "action_stop"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 当前帧处理器，供悬浮窗同步开关状态 */
        @Volatile
        var processor: FrameProcessor? = null
            private set

        /**
         * 暂停识别（编辑关键词等场景下使用）。
         * 原因：关键词如果明文显示在屏幕上，会被自己识别到并触发清理，
         * 所以编辑关键词时必须先暂停。
         */
        @Volatile
        var paused: Boolean = false

        fun setRecognitionPaused(v: Boolean) {
            paused = v
            Logger.i("Capture", if (v) "已暂停识别" else "已恢复识别")
        }

        /** 当前实际在用的 OCR 引擎名：MLKit-CN（正常）/ NodeText（已降级） */
        @Volatile
        var engineName: String = "-"

        /** 是否走无障碍截图通道：Android 11+ 且开关打开 */
        fun useAccessibilityCapture(): Boolean =
            AppConfig.PREFER_ACCESSIBILITY_CAPTURE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        /** 无障碍截图通道启动：不需要 MediaProjection 授权 */
        fun startAuto(context: Context) {
            val i = Intent(context, ScreenCaptureService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun start(context: Context, resultData: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    private lateinit var ocr: OcrEngine
    private lateinit var frameProcessor: FrameProcessor

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDpi = 0

    private var ocrBusy = false
    private var lastOcrMs = 0L

    private val frameIntervalMs: Long
        get() = (1000L / max(1, AppConfig.TARGET_FPS))

    private val loop = object : Runnable {
        override fun run() {
            if (!isRunning) return
            processOneFrame()
            workerHandler.postDelayed(this, frameIntervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("wuyi-capture").apply { start() }
        workerHandler = Handler(workerThread.looper)
        ocr = OcrEngineRouter(this)
        frameProcessor = FrameProcessor(this)

        // ★ 关键：开关状态必须在服务内部自己读一次。
        // 之前依赖外部调用 ScreenCaptureService.processor?.let{...} 同步，
        // 但 startService 是异步的，外部同步时 processor 还是 null，导致两个开关恒为 false，
        // 表现就是「开启了监测但识别完全没反应」。
        frameProcessor.naYiEnabled = Prefs.isNaYiEnabled(this)
        frameProcessor.guoTuEnabled = Prefs.isGuoTuEnabled(this)
        Logger.i(
            "Capture",
            LogText.fmt(
                LogText.CAPTURE_SWITCH_STATE,
                "naYi" to frameProcessor.naYiEnabled,
                "guoTu" to frameProcessor.guoTuEnabled
            )
        )

        frameProcessor.callback = object : FrameProcessor.Callback {
            override fun onTrigger(reason: String, detail: String) {
                TaskExecutor.execute(this@ScreenCaptureService, reason, detail)
            }
        }
        processor = frameProcessor
        engineName = ocr.name
        Logger.i("Capture", LogText.fmt(LogText.CAPTURE_SERVICE_CREATED, "engine" to ocr.name))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Logger.i("Capture", LogText.CAPTURE_STOP_CMD)
            stopSelf()
            return START_NOT_STICKY
        }
        // 通知构建失败不能让进程崩（上一轮 vector 图标的教训）
        val notif = try {
            buildNotification()
        } catch (e: Exception) {
            Logger.e("Capture", LogText.CAPTURE_NOTIFY_FAIL, e)
            null
        }
        if (notif != null) startForegroundCompat(notif)

        if (isRunning) return START_NOT_STICKY

        // Android 11+ 优先无障碍截图通道：不占用 MediaProjection，不会被录屏抢占
        if (useAccessibilityCapture()) {
            startA11yCaptureLoop()
            return START_NOT_STICKY
        }

        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            Logger.e("Capture", LogText.CAPTURE_NO_AUTH)
            stopSelf()
            return START_NOT_STICKY
        }
        setupProjection(data)
        // MediaProjection 的授权数据只能用一次，服务被杀后重启也没法恢复采集，
        // 所以不用 STICKY，避免空转重启
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        // ★ 关键：前台服务类型必须与实际用途匹配。
        // 走无障碍截图时并没有使用 MediaProjection，若仍声明 mediaProjection 类型，
        // Android 14+ 会按"声明了却不使用"处理，部分版本在启动瞬间直接抛异常
        // （表现就是一点「开始检测」就闪退）。
        val type = if (useAccessibilityCapture()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTI_ID, notification, type)
            } else {
                startForeground(NOTI_ID, notification)
            }
        } catch (e: Throwable) {
            // 用 Throwable：OutOfMemoryError 这类 Error 用 Exception 抓不到
            Logger.e("Capture", LogText.CAPTURE_FG_FAIL, e)
            try {
                startForeground(NOTI_ID, notification)
            } catch (t2: Throwable) {
                Logger.e("Capture", LogText.FLOAT_FG_FALLBACK_FAIL, t2)
            }
        }
    }

    // ============ 通道二：无障碍截图（Android 11+，不受录屏影响） ============

    private val captureExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private var a11yFailCount = 0

    private fun startA11yCaptureLoop() {
        isRunning = true
        Logger.i("Capture", LogText.fmt(LogText.CAPTURE_A11Y_START, "fps" to AppConfig.TARGET_FPS))
        workerHandler.post(a11yLoop)
    }

    private val a11yLoop = object : Runnable {
        override fun run() {
            if (!isRunning) return
            // 暂停时（如正在编辑关键词）不截图，避免识别到编辑框里的关键词
            if (paused) {
                workerHandler.postDelayed(this, 300)
                return
            }
            // 两个开关都关着就不截图，省电
            if (!frameProcessor.naYiEnabled && !frameProcessor.guoTuEnabled) {
                workerHandler.postDelayed(this, 500)
                return
            }
            GuardAccessibilityService.takeScreenshotAsync(captureExecutor) { bmp ->
                if (bmp == null) {
                    a11yFailCount++
                    if (a11yFailCount == 1 || a11yFailCount % 20 == 0) {
                        Logger.w(
                            "Capture",
                            LogText.fmt(LogText.CAPTURE_A11Y_SHOT_FAIL, "n" to a11yFailCount)
                        )
                    }
                } else {
                    a11yFailCount = 0
                    try {
                        val luma = computeBitmapLuma(bmp)
                        checkAllBlack(luma)
                        frameProcessor.onFrame(luma) { maybeOcrBitmap(bmp) }
                    } catch (t: Throwable) {
                        Logger.e("Capture", LogText.CAPTURE_PROCESS_SHOT_FAIL, t)
                    } finally {
                        // OCR 用的是副本，这里可以安全回收原图
                        bmp.recycle()
                    }
                }
                val interval = if (AppConfig.A11Y_SHOT_INTERVAL_MS > 0L) AppConfig.A11Y_SHOT_INTERVAL_MS else frameIntervalMs
                workerHandler.postDelayed(this, interval)
            }
        }
    }

    /** 从 Bitmap 采样算平均亮度（判黑/白屏） */
    private fun computeBitmapLuma(bmp: Bitmap): Int {
        val step = AppConfig.MONO_SAMPLE_STEP
        var sum = 0L
        var count = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sum += (r * 299 + g * 587 + b * 114) / 1000
                count++
                x += step
            }
            y += step
        }
        return if (count == 0) -1 else (sum / count).toInt()
    }

    /** Bitmap 版 OCR 抽帧（复制一份给异步 OCR，避免原图被回收后出错） */
    private fun maybeOcrBitmap(src: Bitmap) {
        if (!frameProcessor.naYiEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastOcrMs < frameIntervalMs) return
        if (ocrBusy && AppConfig.OCR_DROP_BUSY_FRAME) return
        lastOcrMs = now
        val bmp = try {
            // 缩小后再送 OCR：全屏原图在 10fps 下内存压力太大，容易 OOM
            val scale = AppConfig.A11Y_SHOT_OCR_SCALE
            if (scale >= 0.99f) {
                src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
            } else {
                val w = (src.width * scale).toInt().coerceAtLeast(1)
                val h = (src.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(src, w, h, true)
            }
        } catch (t: Throwable) {
            Logger.e("Capture", LogText.CAPTURE_COPY_FAIL, t)
            return
        }
        ocrBusy = true
        ocr.recognize(bmp) { text, engineOk ->
            ocrBusy = false
            engineName = ocr.name
            if (!engineOk) Logger.w("Capture", LogText.CAPTURE_OCR_FRAME_FAIL)
            frameProcessor.onOcrText(text)
            bmp.recycle()
        }
    }

    private fun setupProjection(data: Intent) {
        Logger.i("Capture", LogText.CAPTURE_CREATE_PROJECTION)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try {
            mpm.getMediaProjection(android.app.Activity.RESULT_OK, data)
        } catch (e: Exception) {
            Logger.e("Capture", LogText.CAPTURE_CREATE_PROJECTION_FAIL, e)
            notifyUser(LogText.fmt(LogText.HINT_CAPTURE_FAIL, "err" to e.javaClass.simpleName))
            stopSelf()
            return
        }
        if (p == null) {
            Logger.e("Capture", LogText.CAPTURE_PROJECTION_NULL)
            notifyUser(LogText.HINT_CAPTURE_NO_AUTH)
            stopSelf()
            return
        }
        mediaProjection = p

        // ★★★ Android 14+ 硬性要求：必须在 createVirtualDisplay 之前注册 Callback，
        // ★★★ 否则 createVirtualDisplay 直接抛 IllegalStateException —— 这就是
        // ★★★ Android 16 上「共享了整个屏幕但采集未启动」的原因（Android 9 无此限制）。
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Logger.w("Capture", LogText.CAPTURE_PROJECTION_STOPPED)
                notifyUser(LogText.HINT_CAPTURE_PREEMPTED)
                stopSelf()
            }
        }, Handler(workerThread.looper))
        Logger.i("Capture", LogText.CAPTURE_CALLBACK_OK)

        val metrics = resources.displayMetrics
        screenDpi = metrics.densityDpi
        // 宽高取偶并保底 >= 2，避免个别分辨率下 ImageReader/VirtualDisplay 报参数错误
        screenWidth = ((metrics.widthPixels * AppConfig.CAPTURE_SCALE).toInt() / 2 * 2).coerceAtLeast(2)
        screenHeight = ((metrics.heightPixels * AppConfig.CAPTURE_SCALE).toInt() / 2 * 2).coerceAtLeast(2)
        Logger.i("Capture", LogText.fmt(LogText.CAPTURE_SIZE, "w" to screenWidth, "h" to screenHeight, "dpi" to screenDpi))

        imageReader = try {
            ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 4)
        } catch (e: Exception) {
            Logger.e("Capture", LogText.CAPTURE_IMAGEREADER_FAIL, e)
            notifyUser(LogText.fmt(LogText.HINT_IMAGEREADER_FAIL, "err" to e.javaClass.simpleName))
            stopSelf()
            return
        }
        try {
            virtualDisplay = p.createVirtualDisplay(
                "wuyi-guard",
                screenWidth, screenHeight, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
        } catch (e: Exception) {
            Logger.e("Capture", LogText.CAPTURE_VIRTUALDISPLAY_FAIL, e)
            notifyUser(LogText.fmt(LogText.HINT_VIRTUALDISPLAY_FAIL, "err" to e.javaClass.simpleName))
            stopSelf()
            return
        }

        isRunning = true
        Logger.i(
            "Capture",
            LogText.fmt(
                LogText.CAPTURE_STARTED,
                "w" to screenWidth, "h" to screenHeight,
                "fps" to AppConfig.TARGET_FPS, "engine" to ocr.name
            )
        )
        workerHandler.post(loop)
    }

    /** 主线程弹提示（服务里失败时让用户直接看到原因，不用翻日志） */
    private fun notifyUser(msg: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
    }

    private fun processOneFrame() {
        if (paused) return
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val luma = computeAverageLuma(image)
            checkAllBlack(luma)
            // 条件二
            frameProcessor.onFrame(luma) {
                // 条件一：按帧率节流送 OCR
                maybeOcr(image)
            }
        } catch (e: Exception) {
            Logger.e("Capture", LogText.CAPTURE_FRAME_ERROR, e)
        } finally {
            image.close()
        }
    }

    /** 连续全黑诊断：目标应用若禁止截屏(FLAG_SECURE)，MediaProjection 只能拿到黑屏 */
    private var blackFrameCount = 0

    private fun checkAllBlack(luma: Int) {
        if (luma == 0) {
            blackFrameCount++
            if (blackFrameCount == 30) {
                Logger.w("Capture", LogText.CAPTURE_ALL_BLACK)
            }
        } else {
            blackFrameCount = 0
        }
    }

    private fun maybeOcr(image: Image) {
        if (!frameProcessor.naYiEnabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastOcrMs < frameIntervalMs) return
        if (ocrBusy) {
            if (AppConfig.OCR_DROP_BUSY_FRAME) return
        }
        lastOcrMs = now
        ocrBusy = true
        val bmp = imageToBitmap(image)
        if (bmp == null) {
            ocrBusy = false
            return
        }
        ocr.recognize(bmp) { text, engineOk ->
            ocrBusy = false
            engineName = ocr.name
            if (!engineOk) Logger.w("Capture", LogText.CAPTURE_OCR_FRAME_FAIL2)
            bmp.recycle()
            frameProcessor.onOcrText(text)
        }
    }

    /** 计算平均亮度（步长采样） */
    private fun computeAverageLuma(image: Image): Int {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val step = AppConfig.MONO_SAMPLE_STEP
        val bytes = ByteArray(buffer.remaining())
        buffer.rewind()
        buffer.get(bytes)

        var sum = 0L
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val idx = y * rowStride + x * pixelStride
                if (idx + 2 < bytes.size) {
                    val r = bytes[idx].toInt() and 0xFF
                    val g = bytes[idx + 1].toInt() and 0xFF
                    val b = bytes[idx + 2].toInt() and 0xFF
                    sum += (r * 299 + g * 587 + b * 114) / 1000
                    count++
                }
                x += step
            }
            y += step
        }
        return if (count == 0) -1 else (sum / count).toInt()
    }

    /** Image -> Bitmap（RGBA_8888） */
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val width = image.width + rowPadding / pixelStride
            val bmp = Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            Logger.e("Capture", LogText.CAPTURE_IMG_TO_BMP_FAIL, e)
            null
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "无翼守护后台运行", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val pi = android.app.PendingIntent.getService(
            this, 1, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("无翼守护正在运行")
            .setContentText("屏幕监测中，点击停止")
            // ★ 通知小图标必须是位图（PNG），vector 会让 SystemUI 崩溃并带崩 App
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        processor = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            ocr.close()
        } catch (_: Exception) {
        }
        workerThread.quitSafely()
        try { captureExecutor.shutdownNow() } catch (_: Exception) {}
        Logger.i("Capture", LogText.CAPTURE_STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
