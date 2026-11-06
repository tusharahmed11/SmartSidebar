package com.imtusharahmed.smartsidebar.ui.config

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imtusharahmed.smartsidebar.data.AppRepository
import com.imtusharahmed.smartsidebar.model.AppShortcut
import com.imtusharahmed.smartsidebar.service.SidebarService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConfigUiState(
    val apps: List<AppShortcut> = emptyList(),
    val searchQuery: String = "",
    val isServiceRunning: Boolean = false,
    val isRepositioningEnabled: Boolean = true,
    val pinnedCount: Int = 0
)

class SidebarConfigViewModel(
    private val repository: AppRepository
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")

    val uiState: StateFlow<ConfigUiState> = combine(
        repository.getAllApps(),
        searchQueryFlow,
        repository.isServiceRunning(),
        repository.isRepositioningEnabled()
    ) { allApps, query, isRunning, isRepositioningEnabled ->
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(trimmed, ignoreCase = true) ||
                    it.packageName.contains(trimmed, ignoreCase = true)
            }
        }
        ConfigUiState(
            apps = filtered,
            searchQuery = query,
            isServiceRunning = isRunning,
            isRepositioningEnabled = isRepositioningEnabled,
            pinnedCount = allApps.count { it.isPinned }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ConfigUiState()
    )

    fun onSearchQueryChanged(newQuery: String) {
        searchQueryFlow.value = newQuery
    }

    fun onToggleAppPinned(packageName: String, isPinned: Boolean) {
        viewModelScope.launch {
            repository.togglePinApp(packageName = packageName, isPinned = isPinned)
        }
    }

    fun onSetServiceEnabled(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            repository.setServiceEnabled(enabled)
            if (enabled) {
                repository.resetCircuitBreaker()
                val startIntent = Intent(context, SidebarService::class.java)
                ContextCompat.startForegroundService(context, startIntent)
            } else {
                val stopIntent = Intent(context, SidebarService::class.java).apply {
                    action = SidebarService.ACTION_STOP_SERVICE
                }
                context.stopService(stopIntent)
                repository.setServiceRunningState(false)
            }
        }
    }

    fun ensureServiceStartedIfEnabled(context: Context) {
        viewModelScope.launch {
            val enabled = runCatching { repository.isServiceEnabled().first() }.getOrDefault(false)
            val isRunning = repository.isServiceRunning().first()
            if (enabled && !isRunning && android.provider.Settings.canDrawOverlays(context)) {
                val startIntent = Intent(context, SidebarService::class.java)
                ContextCompat.startForegroundService(context, startIntent)
            }
        }
    }

    fun onToggleRepositioning(enabled: Boolean) {
        viewModelScope.launch {
            repository.setRepositioningEnabled(enabled)
        }
    }

    fun refreshApps() {
        repository.refreshInstalledAppsAsync()
    }
}

