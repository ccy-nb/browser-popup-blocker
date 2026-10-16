package com.trae.popupblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 核心无障碍服务：监听窗口变化，从页面上找出垃圾弹窗并处理掉。
 *
 * 相比上一版的三处关键改动：
 *  1. 去抖不再重置计时器（上一版在事件风暴下扫描任务永远不执行，
 *     导致倒计时开屏广告的「跳过」点不到）；
 *  2. 窗口出现后做多轮重试，覆盖「倒计时结束才出现跳过按钮」的情况；
 *  3. 返回键兜底被限制成「窗口里确实有跳过/关闭文案、但找不到可点击目标时」才触发，
 *     并且默认关闭——它是唯一会替用户离开当前页面的动作。
 */
class PopupAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PopupBlocker"

        /** 同一窗口连续处理超过这个次数就认为点不掉，暂时收手 */
        private const val MAX_ACTIONS_PER_WINDOW = 2

        /** 窗口出现后的多轮重试间隔（毫秒），用于等倒计时结束后才出现的跳过按钮 */
        private val RETRY_INTERVALS = longArrayOf(1000L, 1200L, 1600L, 2200L, 3000L)

        /** 这些系统界面绝不处理，避免把系统自己的窗口当成弹窗 */
        private val SYSTEM_DENY = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "android"
        )
    }

    private val prefs: Prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val knownBrowsers: Set<String> by lazy { AppUtils.browsers(this) }

    private var lastWindowSignature: String? = null
    private var lastActionAt = 0L

    private var actionWindowSignature: String? = null
    private var actionsInCurrentWindow = 0

    private var retryIndex = 0

    private val scanTask = Runnable { runScan() }

    private val retryTask = object : Runnable {
        override fun run() {
            runScan()
            retryIndex++
            if (retryIndex < RETRY_INTERVALS.size) {
                handler.postDelayed(this, RETRY_INTERVALS[retryIndex])
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "服务已连接，识别到浏览器 ${knownBrowsers.size} 个")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancelAll()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        cancelAll()
    }

    override fun onDestroy() {
        cancelAll()
        super.onDestroy()
    }

    private fun cancelAll() {
        handler.removeCallbacks(scanTask)
        handler.removeCallbacks(retryTask)
        retryIndex = 0
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled || prefs.isPaused()) return

        val pkg = event.packageName?.toString() ?: return
        if (!shouldProtect(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val signature = "$pkg|${event.className}|${event.windowId}"
                if (signature == lastWindowSignature) return
                lastWindowSignature = signature
                scheduleScan()
                armRetries()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleScan()
        }
    }

    /**
     * 关键修复：只在没有排程时才排，绝不做 remove + postDelayed。
     *
     * 上一版每次事件都重置计时器，而开屏广告的倒计时动画会持续不断地产生内容变化事件，
     * 计时器被无限往后推，扫描任务实际上永远不会执行——这就是「跳过」点不到的根因。
     */
    private fun scheduleScan() {
        if (handler.hasCallbacks(scanTask)) return
        handler.postDelayed(scanTask, prefs.clickDelayMs)
    }

    private fun armRetries() {
        handler.removeCallbacks(retryTask)
        retryIndex = 0
        handler.postDelayed(retryTask, RETRY_INTERVALS[0])
    }

    private fun shouldProtect(pkg: String): Boolean {
        if (pkg == packageName) return false
        if (SYSTEM_DENY.contains(pkg)) return false
        return when (prefs.mode) {
            ProtectMode.ALL -> true
            ProtectMode.BROWSERS -> knownBrowsers.contains(pkg)
            ProtectMode.SELECTED -> prefs.selectedApps.contains(pkg)
        }
    }

    private fun runScan() {
        if (!prefs.enabled || prefs.isPaused()) return
        if (SystemClock.uptimeMillis() - lastActionAt < prefs.cooldownMs) return
        if (isActionExhausted()) return

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!shouldProtect(pkg)) return

        val metrics = resources.displayMetrics
        val detector = PopupDetector(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            density = metrics.density,
            aggressive = prefs.aggressive
        )
        val result = detector.scan(root)

        // 首选：直接点掉关闭按钮
        val candidate = result.closeCandidate
        if (candidate != null && prefs.clickClose) {
            if (click(candidate.node)) {
                record(pkg, candidate.reason)
                return
            }
        }

        // 兜底：窗口里确实存在关闭文案，但一个可点击目标都没找到，才允许按返回
        if (prefs.backFallback && candidate == null && result.hasCloseText) {
            if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                record(pkg, "窗口内有「关闭/跳过」文案但无可点击目标，执行返回键兜底")
            }
        }
    }

    private fun isActionExhausted(): Boolean {
        val signature = lastWindowSignature ?: return false
        return signature == actionWindowSignature && actionsInCurrentWindow >= MAX_ACTIONS_PER_WINDOW
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 3) {
            if (parent.isClickable && parent.isEnabled) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
            hops++
        }
        return false
    }

    private fun record(pkg: String, reason: String) {
        lastActionAt = SystemClock.uptimeMillis()
        cancelAll()

        val signature = lastWindowSignature
        if (signature != null && signature == actionWindowSignature) {
            actionsInCurrentWindow++
        } else {
            actionWindowSignature = signature
            actionsInCurrentWindow = 1
        }

        BlockLogStore.add(
            this,
            BlockRecord(
                time = System.currentTimeMillis(),
                packageName = pkg,
                appLabel = AppUtils.label(this, pkg),
                reason = reason
            )
        )
        Log.i(TAG, "已处理 [$pkg] $reason")
    }
}
