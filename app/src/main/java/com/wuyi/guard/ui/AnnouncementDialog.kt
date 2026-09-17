package com.wuyi.guard.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.fragment.app.DialogFragment
import com.wuyi.guard.AppConfig
import com.wuyi.guard.LogText
import com.wuyi.guard.R
import com.wuyi.guard.databinding.DialogAnnouncementBinding
import com.wuyi.guard.util.Logger

/**
 * 公告弹窗。
 *
 * 内容在 AppConfig.ANNOUNCEMENT_TITLE / ANNOUNCEMENT_BODY 里改。
 * 底部是关闭按钮（文字也在 AppConfig.ANNOUNCEMENT_BUTTON_TEXT 改）。
 */
class AnnouncementDialog : DialogFragment() {

    var onDismissed: (() -> Unit)? = null

    private var _binding: DialogAnnouncementBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AnnouncementDialogTheme)
        isCancelable = false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogAnnouncementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvAnnounceTitle.text = AppConfig.ANNOUNCEMENT_TITLE
        binding.tvAnnounceBody.text = AppConfig.ANNOUNCEMENT_BODY
        binding.btnClose.text = AppConfig.ANNOUNCEMENT_BUTTON_TEXT

        // 入场：淡入 + 轻微放大（回弹），视觉上更"丝滑"
        view.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.dialog_enter))

        binding.btnClose.setOnClickListener {
            val exit = AnimationUtils.loadAnimation(requireContext(), R.anim.dialog_exit)
            exit.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationRepeat(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) {
                    Logger.i("Announcement", LogText.ANNOUNCEMENT_CLICK_CLOSE)
                    onDismissed?.invoke()
                    dismissAllowingStateLoss()
                }
            })
            view.startAnimation(exit)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(dm)
            setLayout((dm.widthPixels * 0.88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setDimAmount(0.45f)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = AnnouncementDialog()
    }
}
