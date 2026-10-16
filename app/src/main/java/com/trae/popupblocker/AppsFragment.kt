package com.trae.popupblocker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.trae.popupblocker.databinding.FragmentAppsBinding

/** 应用页：选择保护范围，并手动勾选要保护的应用 */
class AppsFragment : Fragment() {

    private var binding: FragmentAppsBinding? = null
    private lateinit var prefs: Prefs
    private var adapter: AppAdapter? = null
    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentAppsBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())

        val appAdapter = AppAdapter { pkg, checked -> onToggleApp(pkg, checked) }
        adapter = appAdapter

        binding?.rvApps?.layoutManager = LinearLayoutManager(requireContext())
        binding?.rvApps?.adapter = appAdapter

        binding?.radioGroup?.setOnCheckedChangeListener { _, id -> onModeChanged(id) }
        binding?.etSearch?.doAfterTextChanged { text ->
            appAdapter.filter(text?.toString().orEmpty())
            refreshEmptyState()
        }

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        syncMode()
        refreshListState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        adapter = null
    }

    /** 首次读取应用列表放到后台线程，避免主线程被 PackageManager 卡住 */
    private fun loadApps() {
        if (loading) return
        loading = true
        val appContext = requireContext().applicationContext

        Thread {
            val apps = AppUtils.apps(appContext)
            activity?.runOnUiThread {
                loading = false
                val view = binding ?: return@runOnUiThread
                adapter?.submit(apps)
                adapter?.setSelected(prefs.selectedApps)
                refreshListState()
                refreshEmptyState()
            }
        }.start()
    }

    private fun syncMode() {
        val view = binding ?: return
        val target = when (prefs.mode) {
            ProtectMode.BROWSERS -> R.id.rbBrowsers
            ProtectMode.SELECTED -> R.id.rbSelected
            ProtectMode.ALL -> R.id.rbAll
        }
        if (view.radioGroup.checkedRadioButtonId != target) {
            view.radioGroup.check(target)
        }
    }

    private fun onModeChanged(id: Int) {
        prefs.mode = when (id) {
            R.id.rbSelected -> ProtectMode.SELECTED
            R.id.rbAll -> ProtectMode.ALL
            else -> ProtectMode.BROWSERS
        }
        refreshListState()
        (activity as? MainActivity)?.refreshStatus()
    }

    private fun onToggleApp(pkg: String, checked: Boolean) {
        val selected = prefs.selectedApps.toMutableSet()
        if (checked) selected.add(pkg) else selected.remove(pkg)
        prefs.selectedApps = selected

        adapter?.setSelected(selected)
        binding?.tvSelectedCount?.text =
            getString(R.string.app_selected_count, selected.size)
        (activity as? MainActivity)?.refreshStatus()
    }

    private fun refreshListState() {
        val view = binding ?: return
        val selectable = prefs.mode == ProtectMode.SELECTED

        view.tvLockedHint.visibility = if (selectable) View.GONE else View.VISIBLE
        view.tvSelectedCount.text = getString(R.string.app_selected_count, prefs.selectedApps.size)
        view.etSearch.isEnabled = selectable
        view.rvApps.alpha = if (selectable) 1f else 0.45f
        adapter?.setLocked(!selectable)
    }

    private fun refreshEmptyState() {
        val view = binding ?: return
        val hasItems = (adapter?.itemCount ?: 0) > 0
        view.tvEmpty.visibility = if (hasItems) View.GONE else View.VISIBLE
        view.tvEmpty.setText(R.string.apps_empty)
    }
}
