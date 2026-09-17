package com.wuyi.guard.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wuyi.guard.AppConfig
import com.wuyi.guard.BuildConfig
import com.wuyi.guard.LogText
import com.wuyi.guard.R
import com.wuyi.guard.databinding.FragmentMoreBinding
import com.wuyi.guard.service.GuardAccessibilityService
import com.wuyi.guard.util.Logger
import com.wuyi.guard.util.Prefs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「更多」页。
 *
 * 分区：规则设置 / 悬浮窗 / 清理设置 / 清理修复 / 运行日志 / 关于。
 * 所有文案都在 res/values/strings.xml，可调参数都在 AppConfig.kt。
 */
class MoreFragment : Fragment() {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRules()
        setupFloat()
        setupClearSettings()
        setupFixClear()
        setupLog()
        setupAbout()
    }

    /* ================= 1. 规则设置 ================= */

    private fun setupRules() {
        binding.rowKeyword.setOnClickListener { showKeywordDialog() }
        binding.rowSensitivity.setOnClickListener { showSensitivityDialog() }
        refreshRulesUi()
    }

    /**
     * 注意：**绝不能把关键词明文显示在这里**。
     * 关键词一旦出现在屏幕上，检测开启时就会被自己识别到并触发清理。
     * 所以只显示数量，内容一律打码。
     */
    private fun refreshRulesUi() {
        val kws = Prefs.getKeywords(requireContext())
        binding.tvKeywordValue.text = if (kws.isEmpty()) {
            getString(R.string.rule_keyword_none)
        } else {
            getString(R.string.rule_keyword_hidden).replace("{count}", kws.size.toString())
        }
        binding.tvSensitivityValue.text = if (Prefs.getMonoSensitivity(requireContext()) == AppConfig.MONO_SENSITIVITY_SENSITIVE) {
            getString(R.string.rule_level_sensitive)
        } else {
            getString(R.string.rule_level_default)
        }
    }

    /**
     * 编辑关键词。三重保护，避免"改关键词把自己触发了"：
     *   1. 默认打码显示（每个字显示成 ●，保留分隔符）
     *   2. 打开期间自动暂停识别，关闭后恢复
     *   3. 未点「显示」且未改动时不写回原文（避免把掩码存进去）
     */
    private fun showKeywordDialog() {
        val ctx = requireContext()
        val raw = Prefs.getKeywordsRaw(ctx)
        val separators = AppConfig.KEYWORD_SEPARATORS.toCharArray()
        var revealed = false
        var dirty = false
        var suppress = false

        // 保留分隔符，其余字符打码
        fun maskOf(text: String): String = buildString {
            for (ch in text) append(if (separators.contains(ch)) ch else '●')
        }

        val input = EditText(ctx).apply {
            hint = getString(R.string.rule_keyword_hint)
            // 从顶部开始，避免多行时文字挤在中间
            gravity = android.view.Gravity.TOP
            setSingleLine(false)
            minLines = 3
            maxLines = 5
            textSize = 14f
            // ★ 不要给 EditText 设置 padding：会压缩输入线下划线的绘制空间，
            //   导致多行文字压到底线上（原来的重叠问题就是这么来的）
        }

        fun applyText(text: String) {
            suppress = true
            input.setText(text)
            suppress = false
            input.setSelection(input.text?.length ?: 0)
        }
        applyText(maskOf(raw))

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!suppress) dirty = true
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            // 底部留够空间，防止下划线被裁掉
            setPadding(pad, 4, pad, 16)
            addView(input)
        }

        // 打开期间暂停识别
        com.wuyi.guard.service.ScreenCaptureService.setRecognitionPaused(true)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.rule_keyword)
            .setMessage(R.string.rule_keyword_tip)
            .setView(container)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(R.string.rule_keyword_reveal, null)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val toSave = if (dirty) input.text.toString() else raw
                Prefs.setKeywords(ctx, toSave)
                Logger.i("More", "关键词已更新（${Prefs.getKeywords(ctx).size} 个）")
                Toast.makeText(ctx, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                refreshRulesUi()
            }
            .setOnDismissListener {
                com.wuyi.guard.service.ScreenCaptureService.setRecognitionPaused(false)
            }
            .show()

        // 「显示 / 隐藏」切换（覆盖默认点击，避免点了就关闭）
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            revealed = !revealed
            val text = if (dirty) input.text.toString() else raw
            applyText(if (revealed) text else maskOf(text))
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).text =
                getString(if (revealed) R.string.rule_keyword_hide else R.string.rule_keyword_reveal)
        }
    }

    private fun showSensitivityDialog() {
        val ctx = requireContext()
        val labels = arrayOf(
            getString(R.string.rule_level_default),
            getString(R.string.rule_level_sensitive)
        )
        var selected = Prefs.getMonoSensitivity(ctx)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.rule_sensitivity)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                Prefs.setMonoSensitivity(ctx, selected)
                Logger.i(
                    "More",
                    "黑白屏灵敏度：${if (selected == AppConfig.MONO_SENSITIVITY_SENSITIVE) "灵敏" else "默认"}"
                )
                Toast.makeText(ctx, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                refreshRulesUi()
            }
            .show()
    }

    /* ================= 2. 悬浮窗 ================= */

    private fun setupFloat() {
        binding.switchCompactIcon.isChecked = Prefs.isCollapsedCompact(requireContext())
        binding.switchCompactIcon.setOnCheckedChangeListener { _, checked ->
            Prefs.setCollapsedCompact(requireContext(), checked)
            val dp = Prefs.getCollapsedSizeDp(requireContext())
            Logger.i("More", "折叠图标尺寸切换为 ${dp}dp")
            Toast.makeText(requireContext(), "折叠图标已设为 ${dp}dp", Toast.LENGTH_SHORT).show()
        }
    }

    /* ================= 3. 清理设置（备用清理 + 导航栏） ================= */

    private fun setupClearSettings() {
        binding.switchClearFallback.isChecked = Prefs.isClearFallback(requireContext())
        binding.switchClearFallback.setOnCheckedChangeListener { _, checked ->
            Prefs.setClearFallback(requireContext(), checked)
            Logger.i("More", "清理坐标兜底：${if (checked) "开启" else "关闭"}")
            if (checked) {
                Toast.makeText(requireContext(), R.string.toast_fallback_on, Toast.LENGTH_LONG).show()
            }
            refreshClearSettingsUi()
        }

        binding.switchNavBar.isChecked = Prefs.isNavBarMode(requireContext())
        binding.switchNavBar.setOnCheckedChangeListener { _, checked ->
            Prefs.setNavBarMode(requireContext(), checked)
            Logger.i("More", "导航栏模式：${if (checked) "三键导航栏" else "全面屏手势"}")
            refreshClearSettingsUi()
        }
        refreshClearSettingsUi()
    }

    /** 兜底开关关闭时，导航栏模式置灰不可操作 */
    private fun refreshClearSettingsUi() {
        val ctx = requireContext()
        val enabled = Prefs.isClearFallback(ctx)
        binding.switchNavBar.isEnabled = enabled
        binding.rowNavBar.alpha = if (enabled) 1f else 0.45f
        val y = Prefs.fallbackYPercent(ctx)
        binding.tvFallbackPos.text = getString(R.string.fix_clear_pos_label)
            .replace("{x}", AppConfig.CLEAR_FALLBACK_X_PERCENT.toString())
            .replace("{y}", y.toString())
    }

    /* ================= 4. 清理修复（节点树导出） ================= */

    private fun setupFixClear() {
        binding.btnDumpTree.setOnClickListener {
            val tree = GuardAccessibilityService.dumpWindowTree()
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.CHINA).format(Date())
            val path = Logger.writeExtra(requireContext(), "nodetree_$stamp.txt", tree)
            Logger.i("More", "导出节点树：${path ?: "失败"}")
            Toast.makeText(
                requireContext(),
                if (path != null) getString(R.string.toast_dump_ok).replace("{path}", path)
                else getString(R.string.toast_dump_fail),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /* ================= 5. 运行日志 ================= */

    private fun setupLog() {
        binding.tvLogPath.text = Logger.getLogDir(requireContext()).absolutePath
        binding.btnExportLog.setOnClickListener { exportLog(crash = false) }
        binding.btnCrashLog.setOnClickListener { exportLog(crash = true) }
    }

    private fun exportLog(crash: Boolean) {
        if (Build.VERSION.SDK_INT in 23..28) {
            val granted = requireContext().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2001)
                return
            }
        }
        try {
            val ctx = requireContext()
            val prefix = if (crash) "crash_" else "log_"
            val files = Logger.getLogDir(ctx).listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".txt") }
            if (files.isNullOrEmpty()) {
                Toast.makeText(ctx, R.string.toast_export_empty, Toast.LENGTH_SHORT).show()
                return
            }
            val prefixOut = if (crash) "crash" else "wuyi"
            var count = 0
            files.forEach { f ->
                val name = "${prefixOut}_${f.name}"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        ctx.contentResolver.openOutputStream(uri)?.use { os ->
                            f.inputStream().use { it.copyTo(os) }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        ctx.contentResolver.update(uri, values, null, null)
                        count++
                    }
                } else {
                    val out = File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        ), name
                    )
                    FileOutputStream(out).use { os -> f.inputStream().use { it.copyTo(os) } }
                    count++
                }
            }
            Toast.makeText(ctx, getString(R.string.toast_export_ok, count), Toast.LENGTH_SHORT).show()
            Logger.i("More", LogText.fmt(LogText.MORE_EXPORT_OK, "count" to count))
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.toast_export_fail, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
            Logger.e("More", LogText.MORE_EXPORT_FAIL, e)
        }
    }

    /* ================= 6. 关于 ================= */

    private fun setupAbout() {
        binding.tvVersion.text = getString(R.string.more_about_version_prefix) + BuildConfig.VERSION_NAME

        val url = getString(R.string.more_about_source_url)
        binding.tvSource.text = getString(R.string.more_about_source).replace("{url}", url)
        binding.tvSource.movementMethod = LinkMovementMethod.getInstance()
        binding.tvSource.setOnClickListener { openUrl(url) }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Logger.e("More", "打开链接失败", e)
            Toast.makeText(requireContext(), "打开链接失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            exportLog(crash = false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
