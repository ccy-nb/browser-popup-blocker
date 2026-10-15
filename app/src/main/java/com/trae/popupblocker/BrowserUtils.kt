package com.trae.popupblocker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

data class BrowserInfo(val packageName: String, val label: String)

/**
 * 通过 PackageManager 找出设备上所有能打开网页的应用（即浏览器）。
 * 依赖 AndroidManifest 里的 <queries> 声明，不需要 QUERY_ALL_PACKAGES 这种宽泛权限。
 */
object BrowserUtils {

    fun installedBrowsers(context: Context): List<BrowserInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        return resolved.asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { pkg ->
                BrowserInfo(pkg, appLabel(context, pkg))
            }
            .sortedBy { it.label }
            .toList()
    }

    fun appLabel(context: Context, packageName: String): String =
        runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
}
