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
 * 核心原则是**宁可漏，不可误**，这里有三条设计约束：
 *
 * 1. **只在语义确实是「关闭这个浮层」时才动手。**
 *    「不感兴趣」「以后再说」「残忍拒绝」这类词的意思是对内容表态，不是关闭浮层，
 *    它们大量存在于商品卡片和推荐流里。替你点下去等于替你做了决定，
 *    所以 [NEVER_CLICK_WORDS] 里的词一律不碰。
 *
 * 2. **歧义文案必须通过结构校验。**
 *    「关闭广告 / 跳过 / X」语义无歧义，直接点；「关闭 / close」有歧义，
 *    要求它处在一个铺满屏幕的大容器里，才不会点到浏览器工具栏和标签页上的小叉。
 *
 * 3. **列表里的按钮不点。**
 *    推荐流、商品列表里的按钮都长在可滚动的列表容器内，广告浮层的关闭按钮不会。
 *    这条专门拦截“列表项里的表态按钮”这一类误触。
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
            "关闭", "close", "dismiss"
        )

        /**
         * 永不点击：这些词的语义是「对内容表态」或「稍后再处理」，
         * 不是「关闭这个浮层」。商品卡片、推荐流、评分弹窗里到处都是，
         * 替用户点掉等于替他做决定。
         */
        private val NEVER_CLICK_WORDS = listOf(
            "不感兴趣", "残忍拒绝", "以后再说", "稍后再说", "下次再说",
            "不再提示", "不再提醒", "我知道了", "知道了", "暂不", "放弃", "拒绝"
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
        private val NEVER_NORM = NEVER_CLICK_WORDS.map { normalize(it) }

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
                            // 歧义文案必须同时通过两道结构校验：
                            //  1. 处在一个铺满屏幕的大容器里（排除工具栏）；
                            //  2. 不在可滚动列表内（排除列表项里的表态按钮）。
                            val acceptable = match.strong ||
                                (inLargeContainer(node, root) && !inScrollableList(node, root))
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
                                signals += "「${match.word}」未通过结构校验，已跳过"
                            }
                        } else {
                            signals += "「${match.word}」不可点击，跳过"
                        }
                    } else {
                        skipReason(label)?.let { signals += it }
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

        // 内容表态类按钮绝不点击（不感兴趣 / 以后再说 / 残忍拒绝 …）
        if (NEVER_NORM.any { norm.contains(it) }) return null

        // 再排除正经功能按钮（标签页 / 书签 / 菜单 …）
        if (BLOCK_NORM.any { norm.contains(it) }) return null

        STRONG_NORM.firstOrNull { norm.startsWith(it) }?.let { return Match(it, true) }
        MEDIUM_NORM.firstOrNull { norm.startsWith(it) }?.let { return Match(it, false) }
        return null
    }

    /** 只用于日志：说明这段文案为什么没有动手，方便排查 */
    private fun skipReason(label: String): String? {
        val norm = normalize(label)
        NEVER_NORM.firstOrNull { norm.contains(it) }
            ?.let { return "「$it」属于内容表态按钮，已忽略" }
        if (BLOCK_NORM.any { norm.contains(it) } && norm.contains("关闭")) {
            return "已忽略疑似标签页/工具栏的关闭按钮"
        }
        return null
    }

    /**
     * 判断候选是否长在一个可滚动的列表容器里。
     *
     * 推荐流、商品列表、搜索结果里的按钮（尤其“不感兴趣”这类表态按钮）
     * 都嵌在可滚动列表内；而广告浮层的关闭按钮是独立浮层，不在列表里。
     */
    private fun inScrollableList(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var hops = 0
        while (parent != null && parent !== root && hops < 10) {
            if (parent.isScrollable || parent.collectionInfo != null) return true
            val className = parent.className?.toString().orEmpty()
            if (className.contains("RecyclerView") || className.contains("ListView")) return true
            parent = parent.parent
            hops++
        }
        return false
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
        if (inScrollableList(node, root)) return false

        val zoneX = screenWidth * 28 / 100
        val zoneY = screenHeight * 28 / 100
        val nearLeft = rect.centerX() <= zoneX
        val nearRight = rect.centerX() >= screenWidth - zoneX
        val nearTop = rect.centerY() <= zoneY
        val nearBottom = rect.centerY() >= screenHeight - zoneY

        return (nearLeft || nearRight) && (nearTop || nearBottom)
    }

    /**
     * 解析出真正该点击的节点。
     *
     * 关键约束：**只在“小”目标上点击**。
     * 关闭按钮一定是小的；而广告横幅、列表项、整块卡片本身往往也是可点击的
     * （点下去会打开落地页）。上一版会一路向上找可点击祖先，结果可能直接在
     * 广告容器上点了一下，等于替用户打开了广告页面。
     */
    private fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled) {
            return if (isSmallTarget(node)) node else null
        }

        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 3) {
            if (parent.isClickable && parent.isEnabled) {
                // 第一个可点击祖先如果不是小目标，再往上只会更大，直接放弃
                return if (isSmallTarget(parent)) parent else null
            }
            parent = parent.parent
            hops++
        }
        return null
    }

    /** 小目标：面积不超过屏幕的 20%。按钮够小，容器不够。 */
    private fun isSmallTarget(node: AccessibilityNodeInfo): Boolean {
        if (screenArea <= 0L) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val area = rect.width().toLong() * rect.height().toLong()
        return area * 100 <= screenArea * 20
    }
}
