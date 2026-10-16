package com.trae.popupblocker

/**
 * 最近几次扫描的判断结果，只存在内存里，不持久化。
 *
 * 拦截记录只回答「成功处理了什么」，但排查「为什么没处理」需要看到被忽略的原因。
 * 这里保存最近几条带结论的扫描，显示在记录页顶部。
 */
object ScanDiagStore {

    private const val MAX = 4

    /** 相同结论在该时间窗内只记一条，避免内容变化事件刷屏 */
    private const val DEDUPE_MS = 3000L

    private val items = mutableListOf<BlockRecord>()
    private val listeners = mutableListOf<() -> Unit>()

    private var lastSignature: String? = null
    private var lastAt = 0L

    fun all(): List<BlockRecord> = items.toList()

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun add(appLabel: String, packageName: String, outcome: String) {
        val now = System.currentTimeMillis()
        val signature = "$packageName|$outcome"
        if (signature == lastSignature && now - lastAt < DEDUPE_MS) return

        lastSignature = signature
        lastAt = now

        items.add(0, BlockRecord(now, packageName, appLabel, outcome))
        while (items.size > MAX) items.removeAt(items.size - 1)
        listeners.toList().forEach { it() }
    }

    fun clear() {
        items.clear()
        lastSignature = null
        listeners.toList().forEach { it() }
    }
}
