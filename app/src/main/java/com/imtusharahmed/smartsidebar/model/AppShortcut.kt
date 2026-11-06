package com.imtusharahmed.smartsidebar.model

import android.graphics.drawable.Drawable

data class AppShortcut(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isPinned: Boolean,
    val orderIndex: Int = Int.MAX_VALUE
)

data class PersistedSidebarConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val pinnedPackages: List<String> = emptyList(),
    val handleYOffsetPx: Int = DEFAULT_HANDLE_Y_OFFSET_PX,
    val isRepositioningEnabled: Boolean = DEFAULT_IS_REPOSITIONING_ENABLED,
    val isServiceEnabled: Boolean = DEFAULT_IS_SERVICE_ENABLED,
    val recentStartTimestampsEpochMs: List<Long> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val DEFAULT_HANDLE_Y_OFFSET_PX = 300
        const val DEFAULT_IS_REPOSITIONING_ENABLED = true
        const val DEFAULT_IS_SERVICE_ENABLED = false
    }
}

