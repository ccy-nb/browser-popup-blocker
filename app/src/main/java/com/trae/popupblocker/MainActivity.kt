package com.trae.popupblocker

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.CheckBox
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.trae.popupblocker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val logAdapter = BlockLogAdapter()

    private val onLogChanged: () -> Unit = { refreshLogs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        setupSwitches()
        setupDelaySlider()
        setupBrowserList()
        setupLogList()

        binding.btnAccessibility.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure {
                toast(getString(R.string.open_settings_failed))
            }
        }

        binding.btnClearLogs.setOnClickListener {
            if (BlockLogStore.all().isEmpty()) return@setOnClickListener
            BlockLogStore.clear(this)
        }
    }

    override fun onResume() {
        super.onResume()
        BlockLogStore.addListener(onLogChanged)
        refreshServiceStatus()
        refreshSwitches()
        refreshLogs()
    }

    override fun onPause() {
        super.onPause()
        BlockLogStore.removeListener(onLogChanged)
    }

    // ---------------------------------------------------------------- 开关区

    private fun setupSwitches() {
        bindSwitch(binding.swEnabled, { prefs.enabled }, { prefs.enabled = it })
        bindSwitch(binding.swProtectAll, { prefs.protectAllApps }, {
            prefs.protectAllApps = it
            updateBrowserListEnabled()
        })
        bindSwitch(binding.swClickClose, { prefs.clickClose }, { prefs.clickClose = it })
        bindSwitch(binding.swBackFallback, { prefs.backFallback }, { prefs.backFallback = it })
        bindSwitch(binding.swAggressive, { prefs.aggressive }, { prefs.aggressive = it })
    }

    /** 先把当前状态同步到控件，再挂监听，避免初始化时触发一次多余的写入 */
    private fun bindSwitch(
        view: CompoundButton,
        getter: () -> Boolean,
        setter: (Boolean) -> Unit
    ) {
        view.setOnCheckedChangeListener(null)
        view.isChecked = getter()
        view.setOnCheckedChangeListener { _, checked -> setter(checked) }
    }

    private fun refreshSwitches() {
        setupSwitches()
        updateBrowserListEnabled()
    }

    // ------------------------------------------------------------ 延迟调节

    private fun setupDelaySlider() {
        binding.seekDelay.max = 3000
        binding.seekDelay.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean
                ) {
                    binding.tvDelayValue.text = getString(R.string.delay_value, progress)
                    if (fromUser) prefs.clickDelayMs = progress.toLong()
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            }
        )
        val current = prefs.clickDelayMs.toInt()
        binding.seekDelay.progress = current
        binding.tvDelayValue.text = getString(R.string.delay_value, current)
    }

    // -------------------------------------------------------- 浏览器白名单

    private fun setupBrowserList() {
        val container = binding.layoutBrowsers
        container.removeAllViews()

        val browsers = BrowserUtils.installedBrowsers(this)
        val saved = prefs.protectedPackages

        if (browsers.isEmpty()) {
            val tip = CheckBox(this).apply {
                text = getString(R.string.browser_empty)
                isEnabled = false
            }
            container.addView(tip)
            updateBrowserListEnabled()
            return
        }

        val current = mutableSetOf<String>()
        // 用户还没手动勾选过时，默认全部保护
        if (saved.isNotEmpty()) current.addAll(saved)

        browsers.forEach { browser ->
            val checkBox = CheckBox(this).apply {
                text = browser.label
                textSize = 15f
                tag = browser.packageName
                isChecked = if (saved.isEmpty()) true else saved.contains(browser.packageName)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) current.add(browser.packageName)
                    else current.remove(browser.packageName)
                    prefs.protectedPackages = current.toSet()
                }
            }
            container.addView(checkBox)
        }
        updateBrowserListEnabled()
    }

    private fun updateBrowserListEnabled() {
        val enabled = !prefs.protectAllApps
        binding.layoutBrowsers.alpha = if (enabled) 1f else 0.4f
        for (i in 0 until binding.layoutBrowsers.childCount) {
            binding.layoutBrowsers.getChildAt(i).isEnabled = enabled
        }
        binding.tvBrowserHint.text = if (enabled) {
            getString(R.string.browser_hint, binding.layoutBrowsers.childCount)
        } else {
            getString(R.string.browser_protect_all_hint)
        }
    }

    // -------------------------------------------------------------- 拦截记录

    private fun setupLogList() {
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter
    }

    private fun refreshLogs() {
        val records = BlockLogStore.all()
        logAdapter.submit(records)
        binding.tvCount.text = getString(R.string.count_value, BlockLogStore.total(this))
        binding.tvEmpty.visibility =
            if (records.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    // -------------------------------------------------------------- 服务状态

    private fun refreshServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        binding.tvServiceStatus.text = getString(
            if (enabled) R.string.service_on else R.string.service_off
        )
        binding.tvServiceStatus.setTextColor(
            getColor(if (enabled) R.color.status_on else R.color.status_off)
        )
        binding.btnAccessibility.text = getString(
            if (enabled) R.string.open_accessibility_settings else R.string.enable_accessibility
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, PopupAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val accessibilityOn = Settings.Secure.getInt(
            contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        if (!accessibilityOn) return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component != null && component == expected) return true
        }
        return false
    }

    private fun toast(message: CharSequence) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
