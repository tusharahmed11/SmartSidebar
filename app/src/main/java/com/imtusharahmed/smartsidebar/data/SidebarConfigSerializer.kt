package com.imtusharahmed.smartsidebar.data

import com.imtusharahmed.smartsidebar.model.PersistedSidebarConfig
import org.json.JSONArray
import org.json.JSONObject

class SidebarConfigSerializer {

    fun encode(config: PersistedSidebarConfig): String {
        val root = JSONObject()
        root.put(KEY_SCHEMA_VERSION, config.schemaVersion)
        root.put(KEY_HANDLE_Y_OFFSET_PX, config.handleYOffsetPx)
        root.put(KEY_IS_REPOSITIONING_ENABLED, config.isRepositioningEnabled)
        root.put(KEY_IS_SERVICE_ENABLED, config.isServiceEnabled)
        root.put(KEY_HIDE_WHEN_APPS_RUNNING, config.hideWhenAppsRunning)

        val packagesArray = JSONArray()
        config.pinnedPackages.distinct().forEach { pkg ->
            if (pkg.isNotBlank()) {
                packagesArray.put(pkg)
            }
        }
        root.put(KEY_PINNED_PACKAGES, packagesArray)

        val timestampsArray = JSONArray()
        config.recentStartTimestampsEpochMs.forEach { ts ->
            timestampsArray.put(ts)
        }
        root.put(KEY_RECENT_START_TIMESTAMPS, timestampsArray)

        return root.toString()
    }

    fun decode(rawJson: String?): PersistedSidebarConfig {
        if (rawJson.isNullOrBlank()) {
            return PersistedSidebarConfig()
        }
        return runCatching {
            val root = JSONObject(rawJson)
            val schemaVersion = root.optInt(
                KEY_SCHEMA_VERSION,
                PersistedSidebarConfig.CURRENT_SCHEMA_VERSION
            )
            val handleYOffsetPx = root.optInt(
                KEY_HANDLE_Y_OFFSET_PX,
                PersistedSidebarConfig.DEFAULT_HANDLE_Y_OFFSET_PX
            ).coerceAtLeast(0)
            val isRepositioningEnabled = root.optBoolean(
                KEY_IS_REPOSITIONING_ENABLED,
                PersistedSidebarConfig.DEFAULT_IS_REPOSITIONING_ENABLED
            )
            val isServiceEnabled = root.optBoolean(
                KEY_IS_SERVICE_ENABLED,
                PersistedSidebarConfig.DEFAULT_IS_SERVICE_ENABLED
            )
            val hideWhenAppsRunning = root.optBoolean(
                KEY_HIDE_WHEN_APPS_RUNNING,
                PersistedSidebarConfig.DEFAULT_HIDE_WHEN_APPS_RUNNING
            )

            val pinnedPackages = mutableListOf<String>()
            val packagesArray = root.optJSONArray(KEY_PINNED_PACKAGES)
            if (packagesArray != null) {
                for (i in 0 until packagesArray.length()) {
                    val pkg = packagesArray.optString(i, "").trim()
                    if (pkg.isNotEmpty() && !pinnedPackages.contains(pkg)) {
                        pinnedPackages.add(pkg)
                    }
                }
            }

            val timestamps = mutableListOf<Long>()
            val timestampsArray = root.optJSONArray(KEY_RECENT_START_TIMESTAMPS)
            if (timestampsArray != null) {
                for (i in 0 until timestampsArray.length()) {
                    val ts = timestampsArray.optLong(i, -1L)
                    if (ts > 0L) {
                        timestamps.add(ts)
                    }
                }
            }

            PersistedSidebarConfig(
                schemaVersion = schemaVersion,
                pinnedPackages = pinnedPackages,
                handleYOffsetPx = handleYOffsetPx,
                isRepositioningEnabled = isRepositioningEnabled,
                isServiceEnabled = isServiceEnabled,
                hideWhenAppsRunning = hideWhenAppsRunning,
                recentStartTimestampsEpochMs = timestamps
            )
        }.getOrElse {
            PersistedSidebarConfig()
        }
    }

    companion object {
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_PINNED_PACKAGES = "pinned_packages"
        private const val KEY_HANDLE_Y_OFFSET_PX = "handle_y_offset_px"
        private const val KEY_IS_REPOSITIONING_ENABLED = "is_repositioning_enabled"
        private const val KEY_IS_SERVICE_ENABLED = "is_service_enabled"
        private const val KEY_HIDE_WHEN_APPS_RUNNING = "hide_when_apps_running"
        private const val KEY_RECENT_START_TIMESTAMPS = "recent_start_timestamps_epoch_ms"
    }
}

