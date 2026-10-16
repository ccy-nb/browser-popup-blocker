package com.trae.popupblocker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.trae.popupblocker.databinding.FragmentLogsBinding
import com.trae.popupblocker.databinding.ItemBlockLogBinding
import java.util.Date

/** 记录页：最近扫描的诊断结论 + 拦截历史 */
class LogsFragment : Fragment() {

    private var binding: FragmentLogsBinding? = null
    private val adapter = BlockLogAdapter()
    private val listener: () -> Unit = { refresh() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentLogsBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.rvLogs?.layoutManager = LinearLayoutManager(requireContext())
        binding?.rvLogs?.adapter = adapter
        binding?.btnClearLogs?.setOnClickListener {
            BlockLogStore.clear(requireContext())
            ScanDiagStore.clear()
        }
    }

    override fun onResume() {
        super.onResume()
        BlockLogStore.addListener(listener)
        ScanDiagStore.addListener(listener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        BlockLogStore.removeListener(listener)
        ScanDiagStore.removeListener(listener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun refresh() {
        val view = binding ?: return

        val records = BlockLogStore.all()
        adapter.submit(records)
        view.tvCount.text = getString(R.string.count_value, BlockLogStore.total(requireContext()))
        view.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        renderDiags()
    }

    /** 最近几条扫描结论，最多 4 条，直接铺进 LinearLayout，不需要额外的适配器 */
    private fun renderDiags() {
        val view = binding ?: return
        val container = view.layoutDiags
        container.removeAllViews()

        val diags = ScanDiagStore.all()
        view.tvDiagEmpty.visibility = if (diags.isEmpty()) View.VISIBLE else View.GONE

        diags.forEach { diag ->
            val item = ItemBlockLogBinding.inflate(layoutInflater, container, false)
            item.tvApp.text = diag.appLabel
            item.tvTime.text = BlockLogAdapter.TIME_FORMAT.format(Date(diag.time))
            item.tvReason.text = diag.reason
            item.tvPackage.text = diag.packageName
            container.addView(item.root)
        }
    }
}
