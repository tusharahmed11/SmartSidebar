package com.imtusharahmed.smartsidebar.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

class ForegroundAppDetector(private val context: Context) {

    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager

    @Suppress("DEPRECATION")
    fun hasUsageStatsPermission(): Boolean {
        val appOps = appOpsManager ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getHomePackages(): Set<String> {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(homeIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(homeIntent, 0)
        }
        val packages = resolveInfos.mapNotNull { it.activityInfo?.packageName }.toMutableSet()
        packages.add(context.packageName)
        packages.add("com.android.systemui")
        return packages
    }

    fun isExternalAppForeground(homePackages: Set<String>): Boolean {
        if (!hasUsageStatsPermission()) {
            return false
        }
        val usm = usageStatsManager ?: return false
        val now = System.currentTimeMillis()

        var latestEventPkg: String? = null
        val events = usm.queryEvents(now - 5_000L, now)
        val event = UsageEvents.Event()
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestEventPkg = event.packageName
            }
        }

        if (latestEventPkg == null) {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10_000L, now)
            latestEventPkg = stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        }

        if (latestEventPkg.isNullOrBlank()) {
            return false
        }

        return latestEventPkg !in homePackages
    }

    companion object {
        fun openUsageAccessSettings(context: Context) {
            runCatching {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.onFailure {
                val fallback = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(fallback) }
            }
        }
    }
}
