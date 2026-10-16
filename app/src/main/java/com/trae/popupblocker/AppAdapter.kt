package com.trae.popupblocker

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.trae.popupblocker.databinding.ItemAppBinding

/** 应用多选列表：图标 + 名称 + 包名，右侧勾选框 */
class AppAdapter(
    private val onToggle: (packageName: String, checked: Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private val all = mutableListOf<AppInfo>()
    private val shown = mutableListOf<AppInfo>()
    private var selected: Set<String> = emptySet()
    private var locked = false
    private val iconCache = HashMap<String, Drawable?>()

    class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(apps: List<AppInfo>) {
        all.clear()
        all.addAll(apps)
        shown.clear()
        shown.addAll(apps)
        notifyDataSetChanged()
    }

    fun setSelected(packages: Set<String>) {
        selected = packages
        notifyDataSetChanged()
    }

    fun setLocked(value: Boolean) {
        if (locked == value) return
        locked = value
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val keyword = query.trim().lowercase()
        shown.clear()
        if (keyword.isEmpty()) {
            shown.addAll(all)
        } else {
            all.filterTo(shown) {
                it.label.lowercase().contains(keyword) ||
                    it.packageName.lowercase().contains(keyword)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = shown[position]
        val binding = holder.binding

        binding.tvLabel.text = item.label
        binding.tvPackage.text = if (item.isBrowser) {
            "${item.packageName} · ${holder.itemView.context.getString(R.string.app_browser_tag)}"
        } else {
            item.packageName
        }

        val icon = iconCache.getOrPut(item.packageName) {
            AppUtils.icon(holder.itemView.context, item.packageName)
        }
        binding.ivIcon.setImageDrawable(icon)

        // 先清监听再设状态，避免复用视图时误触发回调
        binding.cbSelect.setOnCheckedChangeListener(null)
        binding.cbSelect.isChecked = selected.contains(item.packageName)
        binding.cbSelect.isEnabled = !locked

        binding.root.alpha = if (locked) 0.5f else 1f
        binding.root.setOnClickListener {
            if (locked) return@setOnClickListener
            onToggle(item.packageName, !selected.contains(item.packageName))
        }
    }

    override fun getItemCount(): Int = shown.size
}
