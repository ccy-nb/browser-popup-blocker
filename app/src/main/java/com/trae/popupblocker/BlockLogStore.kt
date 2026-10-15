package com.trae.popupblocker

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class BlockRecord(
    val time: Long,
    val packageName: String,
    val appLabel: String,
    val reason: String
)

/**
 * 拦截记录仓库。内存里保留最近 [MAX] 条，同时持久化到 SharedPreferences，
 * 界面通过监听器感知变化。
 */
object BlockLogStore {

    private const val MAX = 200
    private const val KEY_RECORDS = "records"
    private const val KEY_TOTAL = "total"

    private val records = mutableListOf<BlockRecord>()
    private val listeners = mutableListOf<() -> Unit>()
    private var loaded = false
    private var total = 0

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 最近一次拦截记录，新的在前 */
    fun all(): List<BlockRecord> {
        return records.toList()
    }

    /** 历史累计拦截次数 */
    fun total(context: Context): Int {
        ensureLoaded(context)
        return total
    }

    fun add(context: Context, record: BlockRecord) {
        ensureLoaded(context)
        records.add(0, record)
        while (records.size > MAX) records.removeAt(records.size - 1)
        total++
        persist(context)
        notifyChanged()
    }

    fun clear(context: Context) {
        ensureLoaded(context)
        records.clear()
        total = 0
        persist(context)
        notifyChanged()
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val sp = context.applicationContext
            .getSharedPreferences("popup_blocker", Context.MODE_PRIVATE)
        total = sp.getInt(KEY_TOTAL, 0)
        val raw = sp.getString(KEY_RECORDS, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    records.add(
                        BlockRecord(
                            time = obj.optLong("t"),
                            packageName = obj.optString("p"),
                            appLabel = obj.optString("l"),
                            reason = obj.optString("r")
                        )
                    )
                }
            }
        }
        loaded = true
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject().apply {
                    put("t", r.time)
                    put("p", r.packageName)
                    put("l", r.appLabel)
                    put("r", r.reason)
                }
            )
        }
        context.applicationContext
            .getSharedPreferences("popup_blocker", Context.MODE_PRIVATE)
            .edit {
                putString(KEY_RECORDS, array.toString())
                putInt(KEY_TOTAL, total)
            }
    }
}
