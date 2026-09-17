package com.wuyi.guard.ui

import com.wuyi.guard.LogText

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.wuyi.guard.R
import com.wuyi.guard.databinding.FragmentHomeBinding
import com.wuyi.guard.engine.TaskExecutor
import com.wuyi.guard.service.FloatingWindowService
import com.wuyi.guard.service.GuardAccessibilityService
import com.wuyi.guard.service.ScreenCaptureService
import com.wuyi.guard.util.Logger
import com.wuyi.guard.util.PermissionHelper
import com.wuyi.guard.util.Prefs

/**
 * 主界面：权限申请引导 + 功能开关。
 *
 * 需要引导用户授权的：
 *   1. 无障碍服务
 *   2. 悬浮窗（显示在应用上层）
 *   3. 电池优化白名单
 *   4. 屏幕采集（MediaProjection，Android 9 上截图的唯一合法方式）
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /** MediaProjection 授权回调 */
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Prefs.setCaptureGranted(requireContext(), true)
            ScreenCaptureService.start(requireContext(), result.data!!)
            toast(R.string.toast_monitor_started)
            Logger.i("Home", LogText.HOME_CAPTURE_GRANTED)
            // 同步开关状态
            ScreenCaptureService.processor?.let {
                it.naYiEnabled = Prefs.isNaYiEnabled(requireContext())
                it.guoTuEnabled = Prefs.isGuoTuEnabled(requireContext())
            }
            // 延迟检查服务是否真的起来了（Android 14+ 失败时给用户明确提示）
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!ScreenCaptureService.isRunning) {
                    Logger.e("Home", LogText.HOME_CAPTURE_NOT_RUNNING)
                    if (isAdded) {
                        toastLong(R.string.toast_capture_failed)
                    }
                }
                if (_binding != null) refresh()
            }, 2500)
        } else {
            toast(R.string.toast_capture_denied)
            Logger.w("Home", LogText.HOME_CAPTURE_DENIED)
        }
        refresh()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnA11y.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(requireContext())
            toastLong(R.string.toast_a11y_hint)
        }
        binding.btnOverlay.setOnClickListener {
            PermissionHelper.openOverlaySettings(requireContext())
            toastLong(R.string.toast_overlay_hint)
        }
        binding.btnBattery.setOnClickListener {
            PermissionHelper.openBatteryOptimizationSettings(requireContext())
            toastLong(R.string.toast_battery_hint)
        }
        binding.btnCapture.setOnClickListener { requestCapture() }

        binding.btnMonitor.setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                ScreenCaptureService.stop(requireContext())
                toast(R.string.toast_monitor_stopped)
            } else {
                requestCapture()
            }
            refresh()
        }

        // 两个条件开关（与悬浮窗等价，这里也能开，避免悬浮窗出问题时完全没法用）
        binding.switchNaYiHome.isChecked = Prefs.isNaYiEnabled(requireContext())
        binding.switchGuoTuHome.isChecked = Prefs.isGuoTuEnabled(requireContext())

        binding.switchNaYiHome.setOnCheckedChangeListener { _, checked ->
            Prefs.setNaYiEnabled(requireContext(), checked)
            ScreenCaptureService.processor?.naYiEnabled = checked
            Logger.i("Home", LogText.fmt(LogText.SWITCH_NA_YI_STATE, "state" to (if (checked) LogText.STATE_ON else LogText.STATE_OFF)))
            if (checked) hintNeedMonitor()
        }
        binding.switchGuoTuHome.setOnCheckedChangeListener { _, checked ->
            Prefs.setGuoTuEnabled(requireContext(), checked)
            ScreenCaptureService.processor?.guoTuEnabled = checked
            Logger.i("Home", LogText.fmt(LogText.SWITCH_GUO_TU_STATE, "state" to (if (checked) LogText.STATE_ON else LogText.STATE_OFF)))
            if (checked) hintNeedMonitor()
        }

        binding.btnFloat.setOnClickListener {
            if (FloatingWindowService.isShowing) {
                FloatingWindowService.hide(requireContext())
                toast(R.string.toast_float_closed)
            } else {
                if (!PermissionHelper.canDrawOverlays(requireContext())) {
                    toast(R.string.toast_need_overlay)
                    PermissionHelper.openOverlaySettings(requireContext())
                    return@setOnClickListener
                }
                FloatingWindowService.show(requireContext())
                toast(R.string.toast_float_opened)
            }
            refresh()
        }

        binding.btnTestTask.setOnClickListener {
            val ok = TaskExecutor.execute(requireContext(), "手动测试", "主界面点击测试按钮")
            toast(if (ok) R.string.toast_test_ok else R.string.toast_test_fail)
        }

        binding.btnTestTrigger.setOnClickListener {
            // 直接调用一次「条件一命中」的日志与任务，方便调试
            Logger.trigger("拿翼保护(手动模拟)", "用于验证触发链路")
            TaskExecutor.execute(requireContext(), "拿翼保护(手动模拟)", "验证触发链路")
            toast(R.string.toast_test_trigger)
        }

        refresh()
    }

    private fun hintNeedMonitor() {
        if (!ScreenCaptureService.isRunning) {
            toastLong(R.string.toast_switch_need_monitor)
        }
    }

    private fun requestCapture() {
        val ctx = requireContext()

        // Android 13+：没有通知权限时前台服务会出问题，先要权限
        if (PermissionHelper.needNotificationPermission(ctx)) {
            toastLong(R.string.toast_need_notification)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
            return
        }

        if (!PermissionHelper.isAccessibilityEnabled(ctx)) {
            toast(R.string.toast_need_a11y)
        }

        // ★ Android 11+：走无障碍截图通道，不需要 MediaProjection 授权，
        //   也不会被系统录屏抢占（这是解决"一开录屏识屏就失效"的关键）
        if (ScreenCaptureService.useAccessibilityCapture()) {
            if (!PermissionHelper.isAccessibilityEnabled(ctx)) {
                Toast.makeText(ctx, "无障碍截图通道需要无障碍服务，请先开启", Toast.LENGTH_LONG).show()
                return
            }
            ScreenCaptureService.startAuto(ctx)
            Toast.makeText(ctx, "屏幕监测已开启（无障碍通道，不受录屏影响）", Toast.LENGTH_SHORT).show()
            Logger.i("Home", LogText.HOME_A11Y_CHANNEL)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (_binding != null) refresh()
            }, 800)
            return
        }

        val mpm = ContextCompat.getSystemService(ctx, android.media.projection.MediaProjectionManager::class.java)
        if (mpm == null) {
            toast(R.string.toast_no_capture_support)
            return
        }
        try {
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Logger.e("Home", LogText.HOME_LAUNCH_FAIL, e)
            toastLong(R.string.toast_launch_fail, e.message ?: "")
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refresh()
    }

    private fun refresh() {
        val ctx = requireContext()

        // 开关状态以 Prefs 为准（悬浮窗和主界面共用）
        val naYi = Prefs.isNaYiEnabled(ctx)
        val guoTu = Prefs.isGuoTuEnabled(ctx)
        if (binding.switchNaYiHome.isChecked != naYi) binding.switchNaYiHome.isChecked = naYi
        if (binding.switchGuoTuHome.isChecked != guoTu) binding.switchGuoTuHome.isChecked = guoTu

        val a11y = PermissionHelper.isAccessibilityEnabled(ctx)
        setStateText(binding.tvA11yState, a11y)
        binding.btnA11y.text = getString(
            if (a11y) R.string.card_a11y_button_on else R.string.card_a11y_button_off
        )

        val overlay = PermissionHelper.canDrawOverlays(ctx)
        setStateText(binding.tvOverlayState, overlay)
        binding.btnOverlay.text = getString(
            if (overlay) R.string.card_overlay_button_on else R.string.card_overlay_button_off
        )

        val battery = PermissionHelper.isIgnoringBatteryOptimizations(ctx)
        setStateText(binding.tvBatteryState, battery, optional = true)
        binding.btnBattery.text = getString(
            if (battery) R.string.card_battery_button_on else R.string.card_battery_button_off
        )

        val capture = ScreenCaptureService.isRunning
        setStateText(binding.tvCaptureState, capture)
        binding.btnCapture.text = getString(
            if (capture) R.string.card_capture_button_on else R.string.card_capture_button_off
        )

        binding.btnMonitor.text = getString(
            if (capture) R.string.btn_monitor_stop else R.string.btn_monitor_start
        )
        binding.btnFloat.text = getString(
            if (FloatingWindowService.isShowing) R.string.btn_float_close else R.string.btn_float_open
        )

        val ready = a11y && overlay && GuardAccessibilityService.isRunning()
        // ↓ 这 6 条提示语的文案在 res/values/strings.xml 第三节
        binding.tvSummary.text = when {
            !a11y -> getString(R.string.home_summary_need_a11y)
            !overlay -> getString(R.string.home_summary_need_overlay)
            !battery -> getString(R.string.home_summary_need_battery)
            !capture -> getString(R.string.home_summary_need_capture)
            !naYi && !guoTu -> getString(R.string.home_summary_switch_off)
            else -> getString(R.string.home_summary_running)
        }
        binding.tvSummary.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (ready && capture) R.color.state_ok else if (a11y) R.color.state_warn else R.color.state_error
            )
        )
    }

    /** 短提示 —— 文案在 res/values/strings.xml 第九节 */
    private fun toast(resId: Int, vararg args: Any) {
        Toast.makeText(requireContext(), getString(resId, *args), Toast.LENGTH_SHORT).show()
    }

    /** 长提示 —— 文案在 res/values/strings.xml 第九节 */
    private fun toastLong(resId: Int, vararg args: Any) {
        Toast.makeText(requireContext(), getString(resId, *args), Toast.LENGTH_LONG).show()
    }

    private fun setStateText(tv: android.widget.TextView, ok: Boolean, optional: Boolean = false) {
        tv.text = when {
            ok -> getString(R.string.state_granted)
            optional -> getString(R.string.state_optional)
            else -> getString(R.string.state_not_granted)
        }
        tv.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (ok) R.color.state_ok else if (optional) R.color.state_warn else R.color.state_error
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
