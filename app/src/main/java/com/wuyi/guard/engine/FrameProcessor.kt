package com.wuyi.guard.engine

import android.os.SystemClock
import android.content.Context
import com.wuyi.guard.AppConfig
import com.wuyi.guard.util.Prefs
import com.wuyi.guard.LogText
import com.wuyi.guard.util.Logger
import java.util.regex.Pattern

/**
 * 帧分析器：判定两个触发条件。
 *
 *  条件一（拿翼保护）：OCR 文字命中特征词，连续 NA_YI_HIT_FRAMES 帧命中 → 触发
 *  条件二（过图保护）：全屏平均亮度低于黑阈值 / 高于白阈值，持续超过 MONO_DURATION_MS → 触发
 *
 * 触发后有冷却，避免连续轰炸。
 */
class FrameProcessor(private val context: Context) {

    interface Callback {
        /** reason: "拿翼保护" / "过图保护" */
        fun onTrigger(reason: String, detail: String)
    }

    var callback: Callback? = null

    @Volatile
    var naYiEnabled: Boolean = false

    @Volatile
    var guoTuEnabled: Boolean = false

    // 条件一状态
    private var keywordHits = 0

    // 条件二状态
    private var monoStartMs = 0L
    private var monoKind = ""

    // 冷却
    private var lastTriggerMs = 0L

    // 统计
    private var frameCount = 0
    private var statStartMs = 0L
    private var lastLuma = -1

    /** 本帧平均亮度（0~255），-1 表示未取到 */
    fun onFrame(avgLuma: Int, ocrTextProvider: (() -> Unit)? = null) {
        frameCount++
        lastLuma = avgLuma
        if (statStartMs == 0L) statStartMs = SystemClock.elapsedRealtime()
        val elapsed = SystemClock.elapsedRealtime() - statStartMs
        if (elapsed >= 5000) {
            val fps = frameCount * 1000f / elapsed
            Logger.d("Frame", LogText.fmt(LogText.FRAME_STAT,
                "fps" to "%.1f".format(fps), "target" to AppConfig.TARGET_FPS, "luma" to lastLuma))
            frameCount = 0
            statStartMs = SystemClock.elapsedRealtime()
        }

        checkMono(avgLuma)
        ocrTextProvider?.invoke()
    }

    /** OCR 文本回来后调用 */
    fun onOcrText(text: String) {
        if (!naYiEnabled || text.isBlank()) {
            keywordHits = 0
            return
        }
        val hit = matchKeyword(text)
        if (hit != null) {
            keywordHits++
            Logger.d("Cond1", LogText.fmt(LogText.NA_YI_HIT_FRAME,
                "kw" to hit, "hit" to keywordHits, "need" to AppConfig.NA_YI_HIT_FRAMES,
                "text" to text.take(60)))
            if (keywordHits >= AppConfig.NA_YI_HIT_FRAMES) {
                keywordHits = 0
                fire("拿翼保护", LogText.fmt(LogText.NA_YI_TRIGGER_DETAIL, "kw" to hit))
            }
        } else {
            keywordHits = 0
        }
    }

    /** 条件二：全屏黑/白判定 */
    private fun checkMono(luma: Int) {
        if (!guoTuEnabled || luma < 0) {
            monoStartMs = 0L
            monoKind = ""
            return
        }
        val kind = when {
            luma <= Prefs.monoBlackThreshold(context) -> "黑屏"
            luma >= Prefs.monoWhiteThreshold(context) -> "白屏"
            else -> ""
        }
        if (kind.isEmpty()) {
            if (monoKind.isNotEmpty()) Logger.d("Cond2", LogText.fmt(LogText.MONO_BREAK,
                "kind" to monoKind, "luma" to luma))
            monoStartMs = 0L
            monoKind = ""
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (monoKind != kind || monoStartMs == 0L) {
            monoKind = kind
            monoStartMs = now
            Logger.d("Cond2", LogText.fmt(LogText.MONO_START, "kind" to kind, "luma" to luma))
            return
        }
        if (now - monoStartMs >= Prefs.monoDurationMs(context)) {
            val dur = now - monoStartMs
            monoStartMs = 0L
            monoKind = ""
            fire("过图保护", LogText.fmt(LogText.MONO_TRIGGER_DETAIL, "kind" to kind, "dur" to dur, "luma" to luma))
        }
    }

    private fun matchKeyword(text: String): String? {
        var t = text
        if (AppConfig.NA_YI_TRIM_SPACE) t = t.replace(Regex("\\s+"), "")
        for (kw in Prefs.getKeywords(context)) {
            if (kw.isBlank()) continue
            val ok = if (AppConfig.NA_YI_USE_REGEX) {
                try {
                    Pattern.compile(kw).matcher(t).find()
                } catch (e: Exception) {
                    Logger.e("Cond1", LogText.fmt(LogText.COND1_REGEX_INVALID, "kw" to kw), e)
                    false
                }
            } else {
                t.contains(kw, ignoreCase = AppConfig.NA_YI_IGNORE_CASE)
            }
            if (ok) return kw
        }
        return null
    }

    private fun fire(reason: String, detail: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerMs < AppConfig.TRIGGER_COOLDOWN_MS) {
            val left = AppConfig.TRIGGER_COOLDOWN_MS - (now - lastTriggerMs)
            Logger.d("Trigger", LogText.fmt(LogText.COOLDOWN_SKIP, "reason" to reason, "left" to left))
            return
        }
        lastTriggerMs = now
        Logger.trigger(reason, detail)
        callback?.onTrigger(reason, detail)
    }

    fun reset() {
        keywordHits = 0
        monoStartMs = 0L
        monoKind = ""
    }
}
