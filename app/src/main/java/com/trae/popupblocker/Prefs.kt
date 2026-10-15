package com.trae.popupblocker

import android.content.Context
import androidx.core.content.edit

/**
 * 应用配置存储。全部设置项都持久化在 SharedPreferences 里，
 * 无障碍服务进程可以随时读到，不需要额外的进程间通信。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("popup_blocker", Context.MODE_PRIVATE)

    /** 拦截总开关 */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) {
            sp.edit { putBoolean(KEY_ENABLED, value) }
        }

    /** true = 保护所有应用；false = 只保护勾选的浏览器 */
    var protectAllApps: Boolean
        get() = sp.getBoolean(KEY_PROTECT_ALL, false)
        set(value) {
            sp.edit { putBoolean(KEY_PROTECT_ALL, value) }
        }

    /** 已勾选需要保护的浏览器包名；为空表示“默认全选当前已安装的浏览器” */
    var protectedPackages: Set<String>
        get() = sp.getStringSet(KEY_PACKAGES, null)?.toSet() ?: emptySet()
        set(value) {
            sp.edit { putStringSet(KEY_PACKAGES, value) }
        }

    /** 是否自动点击“关闭 / X”按钮 */
    var clickClose: Boolean
        get() = sp.getBoolean(KEY_CLICK_CLOSE, true)
        set(value) {
            sp.edit { putBoolean(KEY_CLICK_CLOSE, value) }
        }

    /** 命中广告特征但找不到关闭按钮时，是否用返回键兜底 */
    var backFallback: Boolean
        get() = sp.getBoolean(KEY_BACK_FALLBACK, true)
        set(value) {
            sp.edit { putBoolean(KEY_BACK_FALLBACK, value) }
        }

    /** 激进模式：连角落里没有文字的小图标也当作关闭按钮 */
    var aggressive: Boolean
        get() = sp.getBoolean(KEY_AGGRESSIVE, true)
        set(value) {
            sp.edit { putBoolean(KEY_AGGRESSIVE, value) }
        }

    /** 触发“返回键兜底”所需的可疑度阈值，越高越保守 */
    var threshold: Int
        get() = sp.getInt(KEY_THRESHOLD, 4)
        set(value) {
            sp.edit { putInt(KEY_THRESHOLD, value.coerceIn(1, 12)) }
        }

    /** 识别到弹窗后延迟多久再动手（毫秒），给页面留出渲染时间 */
    var clickDelayMs: Long
        get() = sp.getLong(KEY_DELAY, 800L)
        set(value) {
            sp.edit { putLong(KEY_DELAY, value.coerceIn(0L, 5000L)) }
        }

    /** 每次拦截后的冷却时间，避免连续误触 */
    val cooldownMs: Long get() = 1500L

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROTECT_ALL = "protect_all"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_CLICK_CLOSE = "click_close"
        private const val KEY_BACK_FALLBACK = "back_fallback"
        private const val KEY_AGGRESSIVE = "aggressive"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_DELAY = "delay"
    }
}
