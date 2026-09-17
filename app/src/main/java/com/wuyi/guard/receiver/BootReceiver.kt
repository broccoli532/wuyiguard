package com.wuyi.guard.receiver

import com.wuyi.guard.LogText

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wuyi.guard.util.Logger
import com.wuyi.guard.util.PermissionHelper
import com.wuyi.guard.util.Prefs

/**
 * 开机自启（默认关闭：AndroidManifest 里 android:enabled="false"）。
 * 需要时把 enabled 改成 true 即可。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Logger.i("Boot", LogText.BOOT_RECEIVED)
        val ctx = context.applicationContext
        // 权限齐全时才自动拉起悬浮窗
        if (PermissionHelper.canDrawOverlays(ctx) &&
            PermissionHelper.isAccessibilityEnabled(ctx) &&
            Prefs.isCaptureGranted(ctx)
        ) {
            Logger.i("Boot", LogText.BOOT_RESTORE)
            // 注意：MediaProjection 授权数据在重启后失效，需要用户手动重新开启监测
        }
    }
}
