package com.trae.popupblocker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build

data class AppInfo(
    val packageName: String,
    val label: String,
    val isBrowser: Boolean
)

/**
 * 应用枚举。依赖 AndroidManifest 里的 <queries> 声明，
 * 不需要 QUERY_ALL_PACKAGES 这种宽泛权限。
 */
object AppUtils {

    @Volatile
    private var appCache: List<AppInfo>? = null

    @Volatile
    private var browserCache: Set<String>? = null

    /** 应用名会被服务反复查询，缓存下来避免每次都走 PackageManager */
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 所有能打开网页的应用的包名 */
    fun browsers(context: Context): Set<String> {
        browserCache?.let { return it }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val result = query(context, intent)
            .map { it.activityInfo.packageName }
            .toSet()
        browserCache = result
        return result
    }

    /** 所有可从桌面启动的应用，按名称排序 */
    fun apps(context: Context): List<AppInfo> {
        appCache?.let { return it }
        val browsers = browsers(context)
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val result = query(context, intent)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { AppInfo(it, label(context, it), browsers.contains(it)) }
            .sortedWith(compareBy({ !it.isBrowser }, { it.label }))
        appCache = result
        return result
    }

    fun label(context: Context, packageName: String): String {
        labelCache[packageName]?.let { return it }

        val resolved = runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

        labelCache[packageName] = resolved
        return resolved
    }

    fun icon(context: Context, packageName: String): Drawable? =
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()

    private fun query(context: Context, intent: Intent): List<ResolveInfo> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    }
}
