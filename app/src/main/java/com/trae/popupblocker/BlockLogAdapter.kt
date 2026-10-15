package com.trae.popupblocker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.trae.popupblocker.databinding.ItemBlockLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 拦截记录列表适配器 */
class BlockLogAdapter : RecyclerView.Adapter<BlockLogAdapter.ViewHolder>() {

    private val items = mutableListOf<BlockRecord>()

    class ViewHolder(val binding: ItemBlockLogBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<BlockRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBlockLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = items[position]
        holder.binding.tvApp.text = record.appLabel
        holder.binding.tvPackage.text = record.packageName
        holder.binding.tvReason.text = record.reason
        holder.binding.tvTime.text = TIME_FORMAT.format(Date(record.time))
    }

    override fun getItemCount(): Int = items.size

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }
}
