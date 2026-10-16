package com.trae.popupblocker

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit

/** 保护范围 */
enum class ProtectMode {
    /** 只保护浏览器（默认，最安全） */
    BROWSERS,

    /** 只保护用户手动勾选的应用 */
    SELECTED,

    /** 保护所有应用（误伤风险最高） */
    ALL
}

/**
 * 应用配置存储。全部设置项持久化在 SharedPreferences 里，
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

    /** 保护范围 */
    var mode: ProtectMode
        get() = runCatching {
            ProtectMode.valueOf(sp.getString(KEY_MODE, null) ?: ProtectMode.BROWSERS.name)
        }.getOrDefault(ProtectMode.BROWSERS)
        set(value) {
            sp.edit { putString(KEY_MODE, value.name) }
        }

    /** 自选应用的包名集合，仅在 [ProtectMode.SELECTED] 下生效 */
    var selectedApps: Set<String>
        get() = sp.getStringSet(KEY_SELECTED_APPS, null)?.toSet() ?: emptySet()
        set(value) {
            sp.edit { putStringSet(KEY_SELECTED_APPS, value) }
        }

    /** 是否自动点击「关闭 / 跳过 / X」 */
    var clickClose: Boolean
        get() = sp.getBoolean(KEY_CLICK_CLOSE, true)
        set(value) {
            sp.edit { putBoolean(KEY_CLICK_CLOSE, value) }
        }

    /**
     * 激进模式：把角落里没有文字的小图标也当成关闭按钮。
     * 命中率更高，但属于“猜”，所以默认关闭。
     */
    var aggressive: Boolean
        get() = sp.getBoolean(KEY_AGGRESSIVE, false)
        set(value) {
            sp.edit { putBoolean(KEY_AGGRESSIVE, value) }
        }

    init {
        migrate()
    }

    /**
     * 配置迁移。
     *
     * 光改默认值是没用的：SharedPreferences 的默认值只在键不存在时生效，
     * 老版本写进去的高风险配置会一直留着。所以升级时显式把危险项落回安全状态。
     */
    private fun migrate() {
        val stored = sp.getInt(KEY_SCHEMA, 0)
        if (stored >= SCHEMA) return

        sp.edit {
            putBoolean(KEY_AGGRESSIVE, false)
            // 返回键兜底已彻底移除，清掉历史键，避免残留值影响判断
            remove("back_fallback")
            putInt(KEY_SCHEMA, SCHEMA)
        }
    }

    /** 识别到窗口变化后延迟多久再扫描（毫秒），给页面留出渲染时间 */
    var clickDelayMs: Long
        get() = sp.getLong(KEY_DELAY, 800L)
        set(value) {
            sp.edit { putLong(KEY_DELAY, value.coerceIn(200L, 3000L)) }
        }

    /** 每次拦截后的冷却时间，避免连续误触 */
    val cooldownMs: Long get() = 1500L

    // ------------------------------------------------------------ 临时暂停

    private var pauseUntil: Long
        get() = sp.getLong(KEY_PAUSE_UNTIL, 0L)
        set(value) {
            sp.edit { putLong(KEY_PAUSE_UNTIL, value) }
        }

    /** 当前是否处于临时暂停状态 */
    fun isPaused(): Boolean = SystemClock.elapsedRealtime() < pauseUntil

    /** 暂停剩余毫秒数，0 表示未暂停 */
    fun pauseRemaining(): Long = (pauseUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    fun pause(minutes: Int) {
        pauseUntil = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    fun resume() {
        pauseUntil = 0L
    }

    companion object {
        /** 配置结构版本：升级时用它触发一次安全回落 */
        private const val SCHEMA = 2

        private const val KEY_SCHEMA = "prefs_schema"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODE = "protect_mode"
        private const val KEY_SELECTED_APPS = "selected_apps"
        private const val KEY_CLICK_CLOSE = "click_close"
        private const val KEY_AGGRESSIVE = "aggressive"
        private const val KEY_DELAY = "delay"
        private const val KEY_PAUSE_UNTIL = "pause_until"
    }
}
