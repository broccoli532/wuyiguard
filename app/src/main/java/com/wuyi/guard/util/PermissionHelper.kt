package com.wuyi.guard.util

import com.wuyi.guard.LogText

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.wuyi.guard.service.GuardAccessibilityService
import com.wuyi.guard.util.Logger

/**
 * 权限检查与跳转。
 *
 * 三项权限：
 *  1. 无障碍服务
 *  2. 悬浮窗（SYSTEM_ALERT_WINDOW，显示为「显示在应用上层」）
 *  3. 电池优化白名单（忽略电池优化）
 *
 * 另外还有一个隐式必要条件：屏幕采集（MediaProjection），
 * 因为 Android 9 没有 AccessibilityService#takeScreenshot（那是 API 30+ 才有的）。
 */
object PermissionHelper {

    /** 无障碍服务是否已开启 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null && !am.isEnabled) return false
        val expected = "${context.packageName}/${GuardAccessibilityService::class.java.name}"
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = android.text.TextUtils.SimpleStringSplitter(':')
        splitter.setString(setting)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Logger.e("Perm", LogText.PERM_A11Y_FAIL, e)
        }
    }

    /** 悬浮窗权限（Android 6+ 用 Settings.canDrawOverlays） */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    @SuppressLint("InlinedApi")
    fun openOverlaySettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.e("Perm", LogText.PERM_OVERLAY_FAIL, e)
        }
    }

    /** 是否已加入电池优化白名单 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            // 部分国产 ROM 拦截了该 Intent，退回电池优化列表页
            try {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Logger.e("Perm", LogText.PERM_BATTERY_FAIL, e2)
            }
        }
    }

    /** 通知权限（Android 13+，前台服务通知需要） */
    fun needNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
