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
 * 动作只有一种：**点击**。绝不模拟返回键——返回会替用户离开当前页面，
 * 对网页内的浮层尤其危险（可能触发历史回退甚至退出应用），所以这个能力被彻底移除了。
 *
 * 另外两条稳定性约束：
 *  - 歧义文案（「关闭」这类）只在一个**刚出现的窗口**里才处理，窗口稳定下来之后不碰，
 *    因为那时用户正在正常操作，屏幕上出现「关闭」多半是用户自己打开的对话；
 *  - 点击目标必须是**小节点**，避免点到可点击的广告容器而打开落地页。
 */
class PopupAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PopupBlocker"

        /** 同一窗口最多处理一次，避免反复动手 */
        private const val MAX_ACTIONS_PER_WINDOW = 1

        /** 窗口出现后多久之内才允许处理“歧义文案”，超时视为用户已在正常操作 */
        private const val FRESH_WINDOW_MS = 8000L

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
    private var windowAppearedAt = 0L
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
                windowAppearedAt = SystemClock.uptimeMillis()
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

        val candidate = result.closeCandidate
        val freshWindow = SystemClock.uptimeMillis() - windowAppearedAt <= FRESH_WINDOW_MS

        // 歧义文案只在一个刚出现的窗口里处理。窗口稳定下来之后不碰，
        // 因为那时用户正在正常操作，屏幕上出现「关闭」多半是他自己打开的对话。
        val eligible = candidate != null &&
            prefs.clickClose &&
            (candidate.confidence >= 10 || freshWindow)

        if (eligible) {
            if (click(candidate.node)) {
                record(pkg, candidate.reason)
                return
            }
        }

        // 没有动作时，把“为什么没动手”记进诊断，供排查漏拦与误拦
        val outcome = when {
            candidate != null && !prefs.clickClose -> "自动点击已关闭，未处理：${candidate.reason}"
            candidate != null && !eligible -> "窗口已稳定，跳过歧义文案：${candidate.reason}"
            candidate != null -> "找到关闭目标但点击未生效：${candidate.reason}"
            result.signals.isNotEmpty() -> result.signals.first()
            result.hasCloseText -> "有关闭文案但无可点击目标"
            else -> null
        }
        if (outcome != null) {
            ScanDiagStore.add(AppUtils.label(this, pkg), pkg, outcome)
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
