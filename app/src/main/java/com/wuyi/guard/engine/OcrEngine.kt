package com.wuyi.guard.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.wuyi.guard.AppConfig
import com.wuyi.guard.LogText
import com.wuyi.guard.service.GuardAccessibilityService
import com.wuyi.guard.util.Logger

/**
 * OCR 引擎抽象。
 *
 * 回调第二个参数 engineOk 的含义（非常重要）：
 *   true  = 引擎**调用成功**，text 可能为空（屏幕上本来就没字，属于正常）
 *   false = 引擎**调用失败**（缺 GMS、模型未下载、异常等）
 *
 * 早期版本只看 text 是否为空来降级，结果「游戏画面暂时没文字」被误判成「引擎坏了」，
 * 进游戏几秒就降级到节点取词，之后再也无法识别游戏画面。现在按 engineOk 判定。
 */
interface OcrEngine {
    val name: String
    fun isAvailable(): Boolean
    fun recognize(bitmap: Bitmap, onResult: (text: String, engineOk: Boolean) -> Unit)
    fun close()
}

/** ML Kit 中文识别 */
class MlKitOcrEngine : OcrEngine {

    override val name: String = "MLKit-CN"

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    @Volatile
    private var available = true

    /** 失败日志节流，避免每帧刷屏 */
    private var failLogCount = 0

    override fun isAvailable(): Boolean = available

    override fun recognize(bitmap: Bitmap, onResult: (String, Boolean) -> Unit) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    // 成功：即便 text 为空也是正常结果（画面上没字）
                    available = true
                    onResult(result.text, true)
                }
                .addOnFailureListener { e ->
                    available = false
                    onResult("", false)
                    failLogCount++
                    if (failLogCount == 1 || failLogCount % 20 == 0) {
                        Logger.e(
                            "OCR",
                            LogText.fmt(
                                LogText.OCR_MLKIT_FAIL,
                                "n" to failLogCount,
                                "err" to "${e.javaClass.simpleName} ${e.message}"
                            ),
                            null
                        )
                    }
                }
        } catch (e: Exception) {
            available = false
            onResult("", false)
            failLogCount++
            if (failLogCount == 1 || failLogCount % 20 == 0) {
                Logger.e("OCR", LogText.fmt(LogText.OCR_MLKIT_EXCEPTION, "err" to "${e.javaClass.simpleName} ${e.message}"), e)
            }
        }
    }

    override fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {
        }
    }
}

/** 无障碍节点取词（降级方案，只能读系统控件文字） */
class NodeTextEngine : OcrEngine {

    override val name: String = "NodeText"

    override fun isAvailable(): Boolean = GuardAccessibilityService.isRunning()

    override fun recognize(bitmap: Bitmap, onResult: (String, Boolean) -> Unit) {
        onResult(GuardAccessibilityService.dumpWindowText(), isAvailable())
    }

    override fun close() {}
}

/**
 * 引擎路由器：优先 ML Kit，只在「引擎真的调用失败」时才降级到节点取词，
 * 并且降级后周期性重试主引擎，一旦恢复就自动切回（自愈）。
 */
class OcrEngineRouter(private val context: Context) : OcrEngine {

    override val name: String get() = if (useFallback) fallback.name else primary.name

    private var primary: OcrEngine = MlKitOcrEngine()
    private var fallback: OcrEngine = NodeTextEngine()

    @Volatile
    private var useFallback = false

    @Volatile
    private var failCount = 0

    @Volatile
    private var lastRetryMs = 0L

    private val current: OcrEngine
        get() = if (useFallback) fallback else primary

    override fun isAvailable(): Boolean = current.isAvailable()

    override fun recognize(bitmap: Bitmap, onResult: (String, Boolean) -> Unit) {
        // 自愈：处于降级状态时，每隔一段时间拿一帧去探测主引擎是否恢复
        // （只探测一帧，失败立刻回到节点取词，不影响正常识别流程）
        if (useFallback && AppConfig.OCR_FALLBACK_RETRY_MS > 0L) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRetryMs >= AppConfig.OCR_FALLBACK_RETRY_MS) {
                lastRetryMs = now
                primary.recognize(bitmap) { text, ok ->
                    if (ok) {
                        useFallback = false
                        failCount = 0
                        Logger.i("OCR", LogText.fmt(LogText.OCR_RECOVERED, "engine" to primary.name))
                        onResult(text, true)
                    } else {
                        Logger.d("OCR", LogText.fmt(LogText.OCR_STILL_DOWN, "engine" to fallback.name))
                        onResult(text, false)
                    }
                }
                return
            }
        }

        current.recognize(bitmap) { text, ok ->
            if (!ok) {
                failCount++
                if (failCount >= AppConfig.OCR_FAIL_TO_FALLBACK &&
                    AppConfig.USE_NODE_TEXT_FALLBACK && !useFallback
                ) {
                    useFallback = true
                    Logger.w(
                        "OCR",
                        LogText.fmt(
                            LogText.OCR_SWITCH_FALLBACK,
                            "engine" to primary.name,
                            "n" to failCount,
                            "fallback" to fallback.name,
                            "sec" to AppConfig.OCR_FALLBACK_RETRY_MS / 1000
                        )
                    )
                }
            } else {
                // 调用成功就清零。注意：text 为空但 ok=true 属于正常，不累计
                failCount = 0
            }
            onResult(text, ok)
        }
    }

    override fun close() {
        primary.close()
        fallback.close()
    }
}
