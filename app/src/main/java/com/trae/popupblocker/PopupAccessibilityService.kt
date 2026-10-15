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
 * 核心无障碍服务：监听窗口变化，从页面上找出垃圾弹窗并自动关掉。
 *
 * 处理流程：
 *  1. 只处理目标应用（默认是所有浏览器）的窗口事件，并且做去抖，等页面渲染稳定；
 *  2. 用 [PopupDetector] 扫描当前窗口的节点树，拿到“关闭目标 + 可疑度”；
 *  3. 有明确关闭按钮就直接点击；只有可疑度达标才用返回键兜底，避免误伤；
 *  4. 每次动作后进入冷却期，防止连点。
 */
class PopupAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PopupBlocker"

        /** 同一个窗口连续拦截超过这个次数，就认为点不掉，暂时收手 */
        private const val MAX_ACTIONS_PER_WINDOW = 3
    }

    private val prefs: Prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val scanTask = Runnable { runScan() }

    private val knownBrowsers: Set<String> by lazy {
        BrowserUtils.installedBrowsers(this).map { it.packageName }.toSet()
    }

    private var lastWindowSignature: String? = null
    private var lastActionAt = 0L

    private var actionWindowSignature: String? = null
    private var actionsInCurrentWindow = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接，已识别浏览器 ${knownBrowsers.size} 个")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(scanTask)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(scanTask)
    }

    override fun onDestroy() {
        handler.removeCallbacks(scanTask)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.enabled) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // 别拦自己
        if (!shouldProtect(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val signature = "$pkg|${event.className}|${event.windowId}"
                if (signature == lastWindowSignature) return
                lastWindowSignature = signature
                scheduleScan()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 页面渲染过程中内容事件非常密集，这里统一去抖
                scheduleScan()
            }
        }
    }

    private fun scheduleScan() {
        handler.removeCallbacks(scanTask)
        handler.postDelayed(scanTask, prefs.clickDelayMs)
    }

    private fun shouldProtect(pkg: String): Boolean {
        if (prefs.protectAllApps) return true
        val selected = prefs.protectedPackages
        // 用户还没手动配置时，默认保护所有已安装浏览器
        if (selected.isEmpty()) return knownBrowsers.contains(pkg)
        return selected.contains(pkg)
    }

    private fun runScan() {
        if (!prefs.enabled) return
        if (SystemClock.uptimeMillis() - lastActionAt < prefs.cooldownMs) return
        if (isActionExhaustedForCurrentWindow()) return

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!shouldProtect(pkg)) return

        val metrics = resources.displayMetrics
        val detector = PopupDetector(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            density = metrics.density,
            aggressive = prefs.aggressive
        )
        val result = detector.scan(root)

        // 优先点“关闭 / X”
        val candidate = result.closeCandidate
        if (candidate != null && prefs.clickClose) {
            if (click(candidate.node)) {
                record(pkg, candidate.reason)
                return
            }
        }

        // 找不到关闭按钮但广告特征明显 → 返回键兜底
        if (prefs.backFallback && result.score >= prefs.threshold) {
            val detail = result.signals.joinToString("、").ifEmpty { "广告特征" }
            if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                record(pkg, "广告特征明显（$detail），执行返回键兜底")
            }
        }
    }

    private fun isActionExhaustedForCurrentWindow(): Boolean {
        val sig = lastWindowSignature
        return sig != null && sig == actionWindowSignature &&
            actionsInCurrentWindow >= MAX_ACTIONS_PER_WINDOW
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
        handler.removeCallbacks(scanTask)

        val sig = lastWindowSignature
        if (sig != null && sig == actionWindowSignature) {
            actionsInCurrentWindow++
        } else {
            actionWindowSignature = sig
            actionsInCurrentWindow = 1
        }

        BlockLogStore.add(
            this,
            BlockRecord(
                time = System.currentTimeMillis(),
                packageName = pkg,
                appLabel = BrowserUtils.appLabel(this, pkg),
                reason = reason
            )
        )
        Log.i(TAG, "已拦截 [$pkg] $reason")
    }
}
