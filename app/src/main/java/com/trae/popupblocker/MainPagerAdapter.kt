package com.trae.popupblocker

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/** 顶部状态卡下面的三个页签：保护 / 应用 / 记录 */
class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> ProtectionFragment()
        1 -> AppsFragment()
        else -> LogsFragment()
    }
}
