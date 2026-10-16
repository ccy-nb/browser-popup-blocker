package com.trae.popupblocker

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** 找到的“可关闭”目标节点 */
class CloseCandidate(
    val node: AccessibilityNodeInfo,
    val reason: String,
    val confidence: Int
)

/** 一次节点树扫描的结果 */
class ScanResult(
    /** 置信度最高的关闭按钮，找不到则为 null */
    val closeCandidate: CloseCandidate?,
    /** 判定依据，用于向用户解释“为什么这么处理” */
    val signals: List<String>,
    /**
     * 窗口里是否存在「关闭 / 跳过」这类文案——哪怕它不可点击。
     * 只有它为 true 时才允许动用返回键兜底，避免在普通页面上乱按返回。
     */
    val hasCloseText: Boolean
)

/**
 * 弹窗检测引擎。
 *
 * 这一版的核心原则是**宁可漏，不可误**：上一版用「广告文案打分」触发动作，
 * 而正常网页里「广告」「推广」「立即下载」这类字眼太常见，结果在用户正常浏览时
 * 频繁误触发返回键。现在：
 *
 *  - 广告文案不再参与判定，只保留“关闭类”文案；
 *  - 关闭文案分两档：明确无歧义的（关闭广告 / 跳过 / X）直接点；
 *    有歧义的（关闭 / ×）必须通过结构校验——它得处在一个“大容器”里，
 *    这样才不会点到浏览器工具栏和标签页上的关闭按钮；
 *  - 反例词黑名单进一步挡掉标签页、书签、菜单这类正经按钮。
 */
class PopupDetector(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val density: Float,
    private val aggressive: Boolean
) {

    private val screenArea: Long = screenWidth.toLong() * screenHeight.toLong()

    /** 命中大容器的最低面积占比；工具栏这类窄条达不到 */
    private val largeContainerRatio = 22

    private class Match(val word: String, val strong: Boolean)

    companion object {
        private const val MAX_NODES = 4000
        private const val MAX_DEPTH = 32

        /** 高置信度：文案本身就说明“这是要关掉当前弹窗”，命中即可点击 */
        private val STRONG_WORDS = listOf(
            "关闭广告", "关闭此广告", "关闭推广", "关闭该广告",
            "跳过广告", "跳过此广告", "跳过该广告", "点击跳过", "跳过",
            "closead", "closethisad", "skipad", "skipthisad", "skip"
        )

        /** 中置信度：常见但存在歧义，必须通过结构校验才点击 */
        private val MEDIUM_WORDS = listOf(
            "关闭", "close", "dismiss", "我知道了", "以后再说", "稍后再说",
            "下次再说", "不再提示", "不再提醒", "残忍拒绝", "不感兴趣"
        )

        /**
         * 反例词：出现这些词的“关闭”属于正经功能按钮，绝对不能点。
         * 这正是上一版会莫名其妙关掉用户标签页 / 退出页面的原因。
         */
        private val BLOCK_WORDS = listOf(
            "标签", "tab", "窗口", "书签", "历史", "下载", "设置", "菜单",
            "扩展", "插件", "隐私", "通知", "无痕", "页面", "刷新", "翻译",
            "分享", "更多", "新建", "收藏", "主页", "返回", "前进", "地址栏",
            "搜索栏", "键盘", "输入法", "播放", "通话", "挂断"
        )

        /** X 形状的字符，必须整段只有这个符号才算数 */
        private val X_GLYPHS = setOf("×", "✕", "✖", "╳", "☓", "✗", "✘", "x", "X")

        private val STRONG_NORM = STRONG_WORDS.map { normalize(it) }
        private val MEDIUM_NORM = MEDIUM_WORDS.map { normalize(it) }
        private val BLOCK_NORM = BLOCK_WORDS.map { normalize(it) }

        /** 去掉空白、零宽字符和变体选择符，让 “✖️” 和 “x” 都能归一化 */
        private fun normalize(label: String): String =
            label.replace(" ", "")
                .replace("\uFE0F", "")
                .replace("\u200B", "")
                .replace("\u00A0", "")
                .lowercase()
    }

    fun scan(root: AccessibilityNodeInfo): ScanResult {
        val signals = mutableListOf<String>()
        var best: CloseCandidate? = null
        var hasCloseText = false
        var cornerNode: AccessibilityNodeInfo? = null

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++

            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val hasBounds = rect.width() > 0 && rect.height() > 0
                val label = if (hasBounds) labelOf(node) else ""

                if (label.isNotEmpty()) {
                    val match = matchClose(label)

                    if (match != null) {
                        hasCloseText = true
                        val target = resolveClickable(node)
                        if (target != null) {
                            // 歧义文案必须处在大容器里，否则很可能是在点工具栏
                            val acceptable = match.strong || inLargeContainer(node, root)
                            if (acceptable) {
                                val confidence = if (match.strong) 10 else 7
                                val previous = best
                                if (previous == null || confidence > previous.confidence) {
                                    best = CloseCandidate(
                                        target,
                                        "命中关闭文案「${match.word}」",
                                        confidence
                                    )
                                }
                            } else {
                                signals += "「${match.word}」疑为工具栏按钮，已跳过"
                            }
                        } else {
                            signals += "「${match.word}」不可点击，跳过"
                        }
                    } else if (isBlocked(label)) {
                        // 命中反例词，明确记录一下，方便排查
                        signals += "已忽略疑似标签页/工具栏的关闭按钮"
                    }
                }

                // 激进模式：角落里没有文字的小图标，且必须处在大容器内
                if (aggressive && best == null && cornerNode == null && hasBounds &&
                    isCornerCloseButton(node, label, rect, root)
                ) {
                    cornerNode = node
                }
            }

            if (depth < MAX_DEPTH && node.childCount > 0) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }

        val close = best
        if (close != null) {
            signals.add(0, close.reason)
            return ScanResult(close, signals, hasCloseText)
        }

        val corner = cornerNode
        if (corner != null) {
            val reason = "疑似角落关闭按钮"
            signals.add(0, reason)
            return ScanResult(CloseCandidate(corner, reason, 5), signals, hasCloseText)
        }

        return ScanResult(null, signals, hasCloseText)
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        return when {
            text.isEmpty() -> desc
            desc.isEmpty() -> text
            else -> "$text $desc"
        }
    }

    private fun matchClose(label: String): Match? {
        val norm = normalize(label)

        // 纯 X 字形：整段只有这个符号才算，避免把 “x264 视频” 之类误判
        if (X_GLYPHS.contains(norm)) return Match(label.trim(), true)

        // 先排除正经功能按钮
        if (BLOCK_NORM.any { norm.contains(it) }) return null

        STRONG_NORM.firstOrNull { norm.startsWith(it) }?.let { return Match(it, true) }
        MEDIUM_NORM.firstOrNull { norm.startsWith(it) }?.let { return Match(it, false) }
        return null
    }

    /** 只用于日志提示，判断这段文案是不是被反例词挡下来了 */
    private fun isBlocked(label: String): Boolean {
        val norm = normalize(label)
        return BLOCK_NORM.any { norm.contains(it) && norm.contains("关闭") }
    }

    /**
     * 结构校验：从候选节点往上走，能不能找到一个面积足够大的祖先容器。
     *
     * 广告弹窗的关闭按钮一定长在一个铺满屏幕的浮层里；而浏览器标签页、
     * 工具栏上的关闭按钮，祖先一路都是窄条，走不到大容器 → 被判为不安全。
     */
    private fun inLargeContainer(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        if (screenArea <= 0L) return false
        var parent = node.parent
        var hops = 0
        while (parent != null && parent !== root && hops < 8) {
            val rect = Rect()
            parent.getBoundsInScreen(rect)
            val area = rect.width().toLong() * rect.height().toLong()
            if (area * 100 >= screenArea * largeContainerRatio) return true
            parent = parent.parent
            hops++
        }
        return false
    }

    private fun isCornerCloseButton(
        node: AccessibilityNodeInfo,
        label: String,
        rect: Rect,
        root: AccessibilityNodeInfo
    ): Boolean {
        if (!node.isClickable || !node.isEnabled) return false

        val norm = normalize(label)
        if (norm.isNotEmpty() && !X_GLYPHS.contains(norm)) return false

        val minSize = (14 * density).toInt()
        if (rect.width() < minSize || rect.height() < minSize) return false

        // 比上一版更严：面积不得超过屏幕 2%
        val area = rect.width().toLong() * rect.height().toLong()
        if (screenArea <= 0L || area * 100 > screenArea * 2) return false

        if (!inLargeContainer(node, root)) return false

        val zoneX = screenWidth * 28 / 100
        val zoneY = screenHeight * 28 / 100
        val nearLeft = rect.centerX() <= zoneX
        val nearRight = rect.centerX() >= screenWidth - zoneX
        val nearTop = rect.centerY() <= zoneY
        val nearBottom = rect.centerY() >= screenHeight - zoneY

        return (nearLeft || nearRight) && (nearTop || nearBottom)
    }

    private fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled) return node
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 3) {
            if (parent.isClickable && parent.isEnabled) return parent
            parent = parent.parent
            hops++
        }
        return null
    }
}
