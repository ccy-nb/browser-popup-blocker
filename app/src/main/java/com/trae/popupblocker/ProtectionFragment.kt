package com.trae.popupblocker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import com.trae.popupblocker.databinding.FragmentProtectionBinding

/** 保护页：总开关、拦截策略、识别延迟、无障碍服务入口 */
class ProtectionFragment : Fragment() {

    private var binding: FragmentProtectionBinding? = null
    private lateinit var prefs: Prefs

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentProtectionBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())

        setupSwitches()
        setupDelay()

        binding?.btnEnableService?.setOnClickListener {
            (activity as? MainActivity)?.openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        setupSwitches()
        refreshServiceState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun setupSwitches() {
        val view = binding ?: return
        bindSwitch(view.swEnabled, { prefs.enabled }, {
            prefs.enabled = it
            refreshServiceState()
        })
        bindSwitch(view.swClickClose, { prefs.clickClose }, { prefs.clickClose = it })
        bindSwitch(view.swBackFallback, { prefs.backFallback }, {
            prefs.backFallback = it
            updateBackWarning()
        })
        bindSwitch(view.swAggressive, { prefs.aggressive }, { prefs.aggressive = it })
        updateBackWarning()
    }

    private fun updateBackWarning() {
        binding?.tvBackWarning?.visibility = if (prefs.backFallback) View.VISIBLE else View.GONE
    }

    private fun bindSwitch(
        view: CompoundButton,
        getter: () -> Boolean,
        setter: (Boolean) -> Unit
    ) {
        // 先同步状态再挂监听，避免初始化时触发一次多余的写入
        view.setOnCheckedChangeListener(null)
        view.isChecked = getter()
        view.setOnCheckedChangeListener { _, checked ->
            setter(checked)
            (activity as? MainActivity)?.refreshStatus()
        }
    }

    private fun setupDelay() {
        val view = binding ?: return
        view.seekDelay.max = 3000
        view.seekDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                view.tvDelayValue.text = getString(R.string.delay_value, progress)
                if (fromUser) prefs.clickDelayMs = progress.toLong().coerceAtLeast(200L)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val current = prefs.clickDelayMs.toInt()
        view.seekDelay.progress = current
        view.tvDelayValue.text = getString(R.string.delay_value, current)
    }

    private fun refreshServiceState() {
        val view = binding ?: return
        val on = (activity as? MainActivity)?.isServiceEnabled() ?: false
        view.tvServiceState.setText(
            if (on) R.string.service_state_on else R.string.service_state_off
        )
        view.tvServiceState.setTextColor(
            requireContext().getColor(if (on) R.color.status_ok else R.color.status_bad)
        )
        view.btnEnableService.setText(
            if (on) R.string.open_accessibility_settings else R.string.enable_accessibility
        )
    }
}
