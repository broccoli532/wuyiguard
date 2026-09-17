package com.wuyi.guard.util

import android.content.Context
import com.wuyi.guard.AppConfig

/**
 * 轻量 SharedPreferences 封装，保存开关状态、悬浮窗位置、公告已读版本等。
 */
object Prefs {

    private const val FILE = "wuyi_guard_prefs"

    private const val K_ANNOUNCEMENT_VERSION = "announcement_version"
    private const val K_NA_YI_ENABLED = "na_yi_enabled"
    private const val K_GUO_TU_ENABLED = "guo_tu_enabled"
    private const val K_FLOAT_X = "float_x"
    private const val K_FLOAT_Y = "float_y"
    private const val K_CAPTURE_GRANTED = "capture_granted"
    private const val K_COLLAPSED_COMPACT = "collapsed_compact"
    private const val K_CLEAR_FALLBACK = "clear_fallback"
    private const val K_NAV_BAR_MODE = "nav_bar_mode"
    private const val K_KEYWORDS = "keywords"
    private const val K_MONO_SENS = "mono_sensitivity"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- 公告 ----------
    fun isAnnouncementShown(ctx: Context): Boolean {
        if (AppConfig.ANNOUNCEMENT_ALWAYS_SHOW) return false
        return sp(ctx).getInt(K_ANNOUNCEMENT_VERSION, -1) >= AppConfig.ANNOUNCEMENT_VERSION
    }

    fun markAnnouncementShown(ctx: Context) {
        sp(ctx).edit().putInt(K_ANNOUNCEMENT_VERSION, AppConfig.ANNOUNCEMENT_VERSION).apply()
    }

    // ---------- 两个开关 ----------
    fun isNaYiEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_NA_YI_ENABLED, AppConfig.DEFAULT_NA_YI_ENABLED)

    fun setNaYiEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_NA_YI_ENABLED, v).apply()

    fun isGuoTuEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_GUO_TU_ENABLED, AppConfig.DEFAULT_GUO_TU_ENABLED)

    fun setGuoTuEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_GUO_TU_ENABLED, v).apply()

    // ---------- 悬浮窗位置 ----------
    fun getFloatPos(ctx: Context): Pair<Int, Int> =
        Pair(
            sp(ctx).getInt(K_FLOAT_X, AppConfig.FLOAT_DEFAULT_X),
            sp(ctx).getInt(K_FLOAT_Y, AppConfig.FLOAT_DEFAULT_Y)
        )

    fun saveFloatPos(ctx: Context, x: Int, y: Int) =
        sp(ctx).edit().putInt(K_FLOAT_X, x).putInt(K_FLOAT_Y, y).apply()

    // ---------- 折叠小图标尺寸档位 ----------
    /** true = 紧凑档 48dp，false = 标准档 56dp */
    fun isCollapsedCompact(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_COLLAPSED_COMPACT, AppConfig.DEFAULT_COLLAPSED_COMPACT)

    fun setCollapsedCompact(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_COLLAPSED_COMPACT, v).apply()

    fun getCollapsedSizeDp(ctx: Context): Int =
        if (isCollapsedCompact(ctx)) AppConfig.FLOAT_COLLAPSED_SIZE_DP_COMPACT
        else AppConfig.FLOAT_COLLAPSED_SIZE_DP

    // ---------- 清理按钮坐标兜底 ----------
    /** 找不到清除按钮时改用坐标点击（初始值取自 AppConfig.CLEAR_FALLBACK_TAP） */
    fun isClearFallback(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_CLEAR_FALLBACK, AppConfig.CLEAR_FALLBACK_TAP)

    /** 导航栏模式：true = 三键/导航栏（点上移），false = 全面屏手势 */
    fun isNavBarMode(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_NAV_BAR_MODE, AppConfig.DEFAULT_NAV_BAR_MODE)

    fun setNavBarMode(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_NAV_BAR_MODE, v).apply()

    /** 纵向兜底位置（按导航栏模式自动选） */
    fun fallbackYPercent(ctx: Context): Float =
        if (isNavBarMode(ctx)) AppConfig.CLEAR_FALLBACK_Y_NAVBAR else AppConfig.CLEAR_FALLBACK_Y_GESTURE

    fun setClearFallback(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_CLEAR_FALLBACK, v).apply()

    // ---------- 规则设置（可在 App 里改） ----------

    /** 关键词原始字符串（默认取 AppConfig.NA_YI_KEYWORDS） */
    fun getKeywordsRaw(ctx: Context): String =
        sp(ctx).getString(K_KEYWORDS, null) ?: AppConfig.NA_YI_KEYWORDS.joinToString(",")

    fun setKeywords(ctx: Context, raw: String) =
        sp(ctx).edit().putString(K_KEYWORDS, raw).apply()

    /** 按中英文逗号 / 分号切分，去掉空白项 */
    fun getKeywords(ctx: Context): List<String> =
        getKeywordsRaw(ctx)
            .split(*AppConfig.KEYWORD_SEPARATORS.toCharArray())
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 黑白屏灵敏度档位 */
    fun getMonoSensitivity(ctx: Context): Int =
        sp(ctx).getInt(K_MONO_SENS, AppConfig.MONO_SENSITIVITY_DEFAULT)

    fun setMonoSensitivity(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(K_MONO_SENS, v).apply()

    /** 按档位取实际阈值 / 时长 */
    fun monoBlackThreshold(ctx: Context): Int =
        if (getMonoSensitivity(ctx) == AppConfig.MONO_SENSITIVITY_SENSITIVE)
            AppConfig.MONO_BLACK_THRESHOLD_SENSITIVE else AppConfig.MONO_BLACK_THRESHOLD

    fun monoWhiteThreshold(ctx: Context): Int =
        if (getMonoSensitivity(ctx) == AppConfig.MONO_SENSITIVITY_SENSITIVE)
            AppConfig.MONO_WHITE_THRESHOLD_SENSITIVE else AppConfig.MONO_WHITE_THRESHOLD

    fun monoDurationMs(ctx: Context): Long =
        if (getMonoSensitivity(ctx) == AppConfig.MONO_SENSITIVITY_SENSITIVE)
            AppConfig.MONO_DURATION_MS_SENSITIVE else AppConfig.MONO_DURATION_MS

    // ---------- 屏幕采集授权标记 ----------
    fun setCaptureGranted(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_CAPTURE_GRANTED, v).apply()

    fun isCaptureGranted(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_CAPTURE_GRANTED, false)
}
