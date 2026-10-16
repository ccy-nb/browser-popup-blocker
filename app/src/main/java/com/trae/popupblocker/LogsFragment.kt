package com.trae.popupblocker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.trae.popupblocker.databinding.FragmentLogsBinding

/** 记录页：拦截历史 + 累计次数 */
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
        }
    }

    override fun onResume() {
        super.onResume()
        BlockLogStore.addListener(listener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        BlockLogStore.removeListener(listener)
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
    }
}
