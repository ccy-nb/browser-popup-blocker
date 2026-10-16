package com.trae.popupblocker

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import com.trae.popupblocker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    /** 暂停期间每秒刷新一次倒计时 */
    private val pausedTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            if (prefs.isPaused()) handler.postDelayed(this, 1000L)
        }
    }

    private val logListener: () -> Unit = { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.viewPager.adapter = MainPagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 2
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = getString(
                when (position) {
                    0 -> R.string.tab_protection
                    1 -> R.string.tab_apps
                    else -> R.string.tab_logs
                }
            )
        }.attach()

        binding.btnEnable.setOnClickListener { openAccessibilitySettings() }
        binding.btnPause.setOnClickListener { togglePause() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        BlockLogStore.addListener(logListener)
        handler.removeCallbacks(pausedTicker)
        if (prefs.isPaused()) handler.postDelayed(pausedTicker, 1000L)
    }

    override fun onPause() {
        super.onPause()
        BlockLogStore.removeListener(logListener)
        handler.removeCallbacks(pausedTicker)
    }

    fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            Toast.makeText(this, R.string.open_settings_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** 供各页签在设置变化后回调，刷新顶部状态卡 */
    fun refreshStatus() {
        val serviceOn = isServiceEnabled()
        val paused = serviceOn && prefs.enabled && prefs.isPaused()

        val titleRes: Int
        val colorRes: Int
        when {
            !serviceOn -> {
                titleRes = R.string.status_disabled
                colorRes = R.color.status_bad
            }
            !prefs.enabled -> {
                titleRes = R.string.status_off
                colorRes = R.color.status_bad
            }
            paused -> {
                titleRes = R.string.status_paused
                colorRes = R.color.status_warn
            }
            else -> {
                titleRes = R.string.status_running
                colorRes = R.color.status_ok
            }
        }

        binding.tvStatusTitle.setText(titleRes)
        binding.tvStatusTitle.setTextColor(getColor(colorRes))
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))

        binding.tvStatusSubtitle.text = when {
            !serviceOn -> getString(R.string.status_sub_disabled)
            !prefs.enabled -> getString(R.string.status_sub_off)
            paused -> getString(
                R.string.status_sub_paused,
                formatRemaining(prefs.pauseRemaining())
            )
            else -> getString(
                R.string.status_sub_running,
                scopeLabel(),
                BlockLogStore.total(this)
            )
        }

        binding.btnEnable.visibility = if (serviceOn) View.GONE else View.VISIBLE
        binding.btnPause.visibility = if (serviceOn) View.VISIBLE else View.GONE
        binding.btnPause.text = getString(
            if (paused) R.string.action_resume else R.string.action_pause
        )
    }

    private fun togglePause() {
        if (prefs.isPaused()) {
            prefs.resume()
        } else {
            prefs.pause(10)
        }
        handler.removeCallbacks(pausedTicker)
        if (prefs.isPaused()) handler.postDelayed(pausedTicker, 1000L)
        refreshStatus()
    }

    private fun formatRemaining(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}分${seconds}秒" else "${seconds}秒"
    }

    private fun scopeLabel(): String = when (prefs.mode) {
        ProtectMode.BROWSERS -> getString(R.string.scope_browsers_short)
        ProtectMode.SELECTED -> getString(R.string.scope_selected_short, prefs.selectedApps.size)
        ProtectMode.ALL -> getString(R.string.scope_all_short)
    }

    /** 读系统设置判断本应用的无障碍服务是否已开启 */
    fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, PopupAccessibilityService::class.java)

        val accessibilityOn = Settings.Secure.getInt(
            contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        if (!accessibilityOn) return false

        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component != null && component == expected) return true
        }
        return false
    }
}
