package com.wuyi.guard

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wuyi.guard.databinding.ActivityMainBinding
import com.wuyi.guard.ui.AnnouncementDialog
import com.wuyi.guard.ui.HomeFragment
import com.wuyi.guard.ui.MoreFragment
import com.wuyi.guard.util.Logger
import com.wuyi.guard.util.PermissionHelper
import com.wuyi.guard.util.Prefs

/**
 * 主 Activity：
 *   1. 进入时弹出公告（丝滑动画），点关闭后进入主界面
 *   2. 底部两格菜单栏：主界面 / 更多
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            switchFragment(HomeFragment(), "home")
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchFragment(HomeFragment(), "home")
                R.id.nav_more -> switchFragment(MoreFragment(), "more")
            }
            true
        }

        requestNotificationIfNeeded()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.bottomNav.selectedItemId != R.id.nav_home) {
                    binding.bottomNav.selectedItemId = R.id.nav_home
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.dialog_exit_title)
                        .setMessage(R.string.dialog_exit_message)
                        .setNegativeButton(R.string.dialog_cancel, null)
                        .setPositiveButton(R.string.dialog_exit) { _, _ -> finish() }
                        .show()
                }
            }
        })

        // 等首帧绘制完再弹公告，动画更顺滑
        window.decorView.post {
            if (!Prefs.isAnnouncementShown(this)) {
                showAnnouncement()
            }
        }
    }

    private fun switchFragment(f: Fragment, tag: String) {
        val cur = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (cur != null && cur::class.java == f::class.java) return
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragment_container, f, tag)
            .commit()
    }

    private fun showAnnouncement() {
        val dialog = AnnouncementDialog.newInstance()
        dialog.onDismissed = {
            Prefs.markAnnouncementShown(this)
            Logger.i("Announcement", LogText.ANNOUNCEMENT_CLOSED)
        }
        dialog.show(supportFragmentManager, "announcement")
    }

    private fun requestNotificationIfNeeded() {
        if (PermissionHelper.needNotificationPermission(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

}
