package com.wuyi.guard.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wuyi.guard.AppConfig
import com.wuyi.guard.LogText
import com.wuyi.guard.service.GuardAccessibilityService
import com.wuyi.guard.util.Logger

/**
 * 触发后执行的具体动作：
 *   1. 回到系统主屏幕
 *   2. 调用「清理后台」流程（打开最近任务 → 点击清除全部 → 回主屏）
 *
 * 说明：Android 没有给第三方 App 提供公开的「杀后台进程」接口
 * （ActivityManager.killBackgroundProcesses 在 Android 9 上已名存实亡，
 * 且对非系统应用基本无效）。
 * 所以这里的实现是「模拟用户操作」：打开最近任务，找到并点击系统的清除按钮。
 */
object TaskExecutor {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var executing = false

    /**
     * @return 是否成功发起（不代表清理一定成功）
     */
    fun execute(context: Context, reason: String, detail: String): Boolean {
        if (executing) {
            Logger.w("Task", LogText.fmt(LogText.TASK_BUSY, "reason" to reason))
            return false
        }
        if (!GuardAccessibilityService.isRunning()) {
            Logger.e("Task", LogText.fmt(LogText.TASK_NO_A11Y, "reason" to reason))
            return false
        }
        executing = true
        Logger.i("Task", LogText.fmt(LogText.TASK_START, "reason" to reason, "detail" to detail))

        // 第一步：回主屏幕
        if (AppConfig.TASK_GO_HOME) {
            val ok = GuardAccessibilityService.goHome()
            Logger.i("Task", LogText.fmt(LogText.TASK_GO_HOME,
                "result" to (if (ok) LogText.RESULT_OK else LogText.RESULT_FAIL)))
        }

        // 第二步：清理后台
        mainHandler.postDelayed({
            if (AppConfig.TASK_CLEAR_RECENT) {
                GuardAccessibilityService.clearBackground { hit ->
                    Logger.i("Task", LogText.fmt(LogText.TASK_CLEAR_RESULT,
                    "result" to (if (hit) LogText.CLEAR_OK else LogText.CLEAR_FAIL)))
                    Logger.i("Task", LogText.TASK_END)
                    executing = false
                }
            } else {
                Logger.i("Task", LogText.TASK_END + LogText.TASK_END_NO_CLEAR)
                executing = false
            }
        }, AppConfig.TASK_DELAY_BEFORE_HOME_MS)

        // 保险：超时强制解锁，防止某一步卡死后所有后续触发都被拒
        mainHandler.postDelayed({
            if (executing) {
                Logger.w("Task", LogText.fmt(LogText.TASK_TIMEOUT, "ms" to AppConfig.TASK_TIMEOUT_MS))
                executing = false
            }
        }, AppConfig.TASK_TIMEOUT_MS)

        return true
    }
}
