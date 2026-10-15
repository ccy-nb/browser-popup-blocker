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
    /** 可疑度得分，越高越像垃圾弹窗 */
    val score: Int,
    /** 命中的特征，用于向用户解释“为什么拦了它” */
    val signals: List<String>
)

/**
 * 弹窗检测引擎。
 *
 * 用“多信号打分 + 取关闭目标”而不是单一规则，因为各家浏览器把弹窗暴露到
 * 无障碍节点树的方式差别很大：
 *
 *  - 原生弹窗（Dialog / 广告 Activity）通常把文字和按钮完整暴露 → 靠关闭关键词命中；
 *  - 网页内弹窗（WebView）多半只暴露 DOM 文本 → 靠广告文案特征打分；
 *  - 纯图片广告往往只剩一个没有文字的角落小图标 → 靠“激进模式”的几何特征兜底。
 *
 * 合并三路信号后：
 *  - 拿到高置信度关闭按钮 → 直接点它；
 *  - 否则看可疑度是否达到阈值 → 交给上层用返回键兜底。
 */
class PopupDetector(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val density: Float,
    private val aggressive: Boolean
) {

    companion object {
        private const val MAX_NODES = 3000
        private const val MAX_DEPTH = 30
        private const val MAX_AD_HITS = 3

        /** 明确的“关闭 / 跳过”语义，命中即可直接点击 */
        private val CLOSE_WORDS = listOf(
            "关闭广告", "关闭此广告", "关闭推广", "关闭",
            "跳过广告", "跳过此广告", "跳过",
            "close ad", "close", "skip ad", "skip", "dismiss", "no thanks",
            "不再提示", "不再提醒", "以后再说", "稍后再说", "下次再说", "残忍拒绝",
            "不感兴趣", "我知道了"
        )

        /**
         * 反例词：命中“关闭”但其实是正经功能按钮，绝不能点。
         * 典型的就是浏览器标签页上的小叉，点了会关掉用户自己的标签。
         */
        private val CLOSE_WORD_BLOCKLIST = listOf(
            "标签", "标签页", "tab", "窗口", "书签", "历史", "下载", "设置", "菜单",
            "关闭键盘", "书签栏", "无痕", "隐私"
        )

        /** X 形状的字符，常见于“点这里关闭”的小图标 */
        private val X_GLYPHS = setOf("×", "✕", "✖", "╳", "☓", "✗", "✘", "x", "X")

        /** 广告文案特征，用于“有广告内容但找不到关闭按钮 → 返回键兜底” */
        private val AD_WORDS = listOf(
            "广告", "推广", "赞助", "advertisement", "sponsored",
            "立即下载", "点击下载", "免费下载", "点击安装", "立即安装",
            "打开app", "打开应用", "摇一摇", "抽奖", "红包", "立即领取", "恭喜你"
        )
    }

    fun scan(root: AccessibilityNodeInfo): ScanResult {
        val signals = mutableListOf<String>()
        val adHits = mutableSetOf<String>()
        var best: CloseCandidate? = null
        var cornerCandidate: AccessibilityNodeInfo? = null
        var cornerReason: String? = null

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
                val label = labelOf(node)

                if (hasBounds && label.isNotEmpty()) {
                    // 信号 1：关闭 / 跳过 文案
                    val hit = matchCloseWord(label)
                    if (hit != null) {
                        val direct = node.takeIf { it.isClickable && it.isEnabled }
                        val target = direct ?: findClickableAncestor(node)
                        if (target != null) {
                            val confidence = if (direct != null) 10 else 7
                            val previous = best
                            if (previous == null || confidence > previous.confidence) {
                                best = CloseCandidate(target, "命中关闭文案「$hit」", confidence)
                            }
                        }
                    }

                    // 信号 2：广告文案特征（每命中一个新词加一分）
                    for (word in AD_WORDS) {
                        if (adHits.size >= MAX_AD_HITS) break
                        if (label.contains(word, ignoreCase = true)) adHits.add(word)
                    }
                }

                // 信号 3：角落里没有文字的关闭小图标（激进模式）
                if (aggressive && cornerCandidate == null && hasBounds &&
                    isCornerCloseButton(node, label, rect)
                ) {
                    cornerCandidate = node
                    cornerReason = "疑似角落关闭按钮"
                }
            }

            if (depth < MAX_DEPTH && node.childCount > 0) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }

        val baseScore = adHits.size
        if (adHits.isNotEmpty()) {
            signals += "广告文案：" + adHits.joinToString("、")
        }

        val close = best
        if (close != null) {
            signals += close.reason
            return ScanResult(close, baseScore + close.confidence, signals)
        }

        val corner = cornerCandidate
        if (corner != null) {
            val reason = cornerReason ?: "疑似角落关闭按钮"
            signals += reason
            return ScanResult(CloseCandidate(corner, reason, 5), baseScore + 5, signals)
        }

        return ScanResult(null, baseScore, signals)
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

    private fun matchCloseWord(label: String): String? {
        val cleaned = cleanGlyph(label)

        // X 字形优先：通常就表示“关闭本弹窗”
        if (X_GLYPHS.contains(cleaned)) return cleaned

        val lower = label.lowercase()
        // 先排除正经功能按钮，避免点到标签页关闭、菜单之类
        if (CLOSE_WORD_BLOCKLIST.any { lower.contains(it.lowercase()) }) return null
        return CLOSE_WORDS.firstOrNull { lower.contains(it.lowercase()) }
    }

    /**
     * 角落里的小图标判定：没有文字（或纯粹是 X）、尺寸很小、位于屏幕四角之一。
     * 浏览器工具栏里的按钮一般带有内容描述（如“重新加载此页面”），
     * 所以要求 label 为空，能挡掉大部分误判。
     */
    private fun isCornerCloseButton(
        node: AccessibilityNodeInfo,
        label: String,
        rect: Rect
    ): Boolean {
        if (!node.isClickable || !node.isEnabled) return false

        val cleaned = cleanGlyph(label)
        if (cleaned.isNotEmpty() && !X_GLYPHS.contains(cleaned)) return false

        val minSize = (12 * density).toInt()
        if (rect.width() < minSize || rect.height() < minSize) return false

        val area = rect.width().toLong() * rect.height().toLong()
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        if (screenArea <= 0L) return false
        if (area * 100 > screenArea * 3) return false // 超过屏幕 3% 面积就不算“小图标”

        val zoneX = screenWidth * 30 / 100
        val zoneY = screenHeight * 30 / 100
        val nearLeft = rect.centerX() <= zoneX
        val nearRight = rect.centerX() >= screenWidth - zoneX
        val nearTop = rect.centerY() <= zoneY
        val nearBottom = rect.centerY() >= screenHeight - zoneY

        return (nearLeft || nearRight) && (nearTop || nearBottom)
    }

    /** 去掉空白和变体选择符，便于把 “✖️” 也识别成 X */
    private fun cleanGlyph(label: String): String =
        label.replace(" ", "")
            .replace("\uFE0F", "")
            .replace("\u200B", "")
            .replace("\u00A0", "")

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 4) {
            if (parent.isClickable && parent.isEnabled) return parent
            parent = parent.parent
            hops++
        }
        return null
    }
}
