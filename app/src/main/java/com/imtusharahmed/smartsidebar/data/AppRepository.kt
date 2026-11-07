package com.imtusharahmed.smartsidebar.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.imtusharahmed.smartsidebar.diagnostics.DiagnosticRingBuffer
import com.imtusharahmed.smartsidebar.model.AppShortcut
import com.imtusharahmed.smartsidebar.model.PersistedSidebarConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val serializer: SidebarConfigSerializer,
    private val ringBuffer: DiagnosticRingBuffer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + ioDispatcher + CoroutineName("SideBar:AppRepository")
    )

    private val installedAppsCache = MutableStateFlow<List<AppShortcut>>(emptyList())
    private val serviceRunningState = MutableStateFlow(false)

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val pkg = intent.data?.schemeSpecificPart
            ringBuffer.record("PACKAGE_MONITOR", "Action=$action pkg=$pkg")
            refreshInstalledAppsAsync()
            if (action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false).not() &&
                !pkg.isNullOrBlank()
            ) {
                repositoryScope.launch {
                    pruneRemovedPackage(pkg)
                }
            }
        }
    }

    init {
        registerPackageMonitor()
        refreshInstalledAppsAsync()
    }

    private fun registerPackageMonitor() {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(
                context,
                packageChangeReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure { error ->
            ringBuffer.record("PACKAGE_MONITOR_ERROR", error.message ?: "Failed to register receiver")
        }
    }

    fun refreshInstalledAppsAsync() {
        repositoryScope.launch {
            val loaded = queryLaunchableApps()
            installedAppsCache.value = loaded
            ringBuffer.record("APP_CACHE", "Cached ${loaded.size} launchable apps")
        }
    }

    private suspend fun queryLaunchableApps(): List<AppShortcut> = withContext(ioDispatcher) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val selfPackage = context.packageName
        resolveInfos
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == selfPackage) return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                AppShortcut(
                    packageName = pkg,
                    label = label,
                    icon = icon,
                    isPinned = false
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private val persistedConfigFlow: Flow<PersistedSidebarConfig> = dataStore.data
        .map { prefs -> serializer.decode(prefs[KEY_SIDEBAR_CONFIG_JSON]) }
        .distinctUntilChanged()

    fun getAllApps(): Flow<List<AppShortcut>> {
        return combine(installedAppsCache, persistedConfigFlow) { installed, config ->
            val pinnedIndexMap = config.pinnedPackages
                .withIndex()
                .associate { (index, pkg) -> pkg to index }

            installed.map { app ->
                val orderIndex = pinnedIndexMap[app.packageName]
                app.copy(
                    isPinned = orderIndex != null,
                    orderIndex = orderIndex ?: Int.MAX_VALUE
                )
            }
        }
    }

    fun getPinnedShortcuts(): Flow<List<AppShortcut>> {
        return getAllApps().map { apps ->
            apps.asSequence()
                .filter { it.isPinned }
                .sortedBy { it.orderIndex }
                .toList()
        }
    }

    fun getHandleYPosition(): Flow<Int> {
        return persistedConfigFlow
            .map { it.handleYOffsetPx }
            .distinctUntilChanged()
    }

    fun isRepositioningEnabled(): Flow<Boolean> {
        return persistedConfigFlow
            .map { it.isRepositioningEnabled }
            .distinctUntilChanged()
    }

    fun isServiceRunning(): Flow<Boolean> = serviceRunningState

    fun setServiceRunningState(isRunning: Boolean) {
        serviceRunningState.value = isRunning
    }

    fun isServiceEnabled(): Flow<Boolean> {
        return persistedConfigFlow
            .map { it.isServiceEnabled }
            .distinctUntilChanged()
    }

    suspend fun setServiceEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            val current = serializer.decode(prefs[KEY_SIDEBAR_CONFIG_JSON])
            val updated = current.copy(isServiceEnabled = enabled)
            prefs[KEY_SIDEBAR_CONFIG_JSON] = serializer.encode(updated)
        }
        ringBuffer.record("CONFIG_UPDATE", "isServiceEnabled=$enabled")
    }

    suspend fun saveHandleYPosition(yPx: Int) = withContext(ioDispatcher) {
        val startMs = System.currentTimeMillis()
        val sanitizedY = yPx.coerceAtLeast(0)
        dataStore.edit { prefs ->
            val current = serializer.decode(prefs[KEY_SIDEBAR_CONFIG_JSON])
            val updated = current.copy(handleYOffsetPx = sanitizedY)
            prefs[KEY_SIDEBAR_CONFIG_JSON] = serializer.encode(updated)
        }
        val elapsedMs = System.currentTimeMillis() - startMs
        if (elapsedMs > 50L) {
            ringBuffer.record("DATASTORE_SLOW_WRITE", "saveHandleYPosition took ${elapsedMs}ms")
        }
    }

    fun isHideWhenAppsRunning(): Flow<Boolean> {
        return persistedConfigFlow
            .map { it.hideWhenAppsRunning }
            .distinctUntilChanged()
    }

    suspend fun setHideWhenAppsRunning(hide: Boolean) = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            val current = serializer.decode(prefs[KEY_SIDEBAR_CONFIG_JSON])
            val updated = current.copy(hideWhenAppsRunning = hide)
            prefs[KEY_SIDEBAR_CONFIG_JSON] = serializer.encode(updated)
        }
        ringBuffer.record("CONFIG_UPDATE", "hideWhenAppsRunning=$hide")
    }

    suspend fun setRepositioningEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            val current = serializer.decode(prefs[KEY_SIDEBAR_CONFIG_JSON])
            val updated = current.copy(isRepositioningEnabled = enabled)
            prefs[KEY_SIDEBAR_CONFIG_JSON] = serializer.encode(updated)
        }
        ringBuffer.record("CONFIG_UPDATE", "isRepositioningEnabled=$enabled")
    }

    suspend fun togglePinApp(packageName: String, isPinned: Boolean) = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            val current = serializer.decode(prefs[KEY_SIDEBAR_CONFIG_JSON])
            val mutablePinned = current.pinnedPackages.toMutableList()
            if (isPinned) {
                if (!mutablePinned.contains(packageName)) {
                    mutablePinned.add(packageName)
                }
            } else {
                mutablePinned.remove(packageName)
            }
            val updated = current.copy(pinnedPackages = mutablePinned)
            prefs[KEY_SIDEBAR_CONFIG_JSON] = serializer.encode(updated)
        }
        ringBuffer.record("PIN_UPDATE", "package=$packageName isPinned=$isPinned")
    }

    private suspend fun pruneRemovedPackage(packageName: String) = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            val current = serializer.decode(prefs[KEY_SIDEBAR_CONFIG_JSON])
            if (current.pinnedPackages.contains(packageName)) {
                val updated = current.copy(
                    pinnedPackages = current.pinnedPackages.filterNot { it == packageName }
                )
                prefs[KEY_SIDEBAR_CONFIG_JSON] = serializer.encode(updated)
                ringBuffer.record("PACKAGE_PRUNED", "Removed uninstalled pinned package=$packageName")
            }
        }
    }

    suspend fun recordServiceStartAndCheckCircuitBreaker(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = withContext(ioDispatcher) {
        var allowed = true
        dataStore.edit { prefs ->
            val current = serializer.decode(prefs[KEY_SIDEBAR_CONFIG_JSON])
            val updatedTimestamps = evaluateRestartTimestamps(
                existingTimestamps = current.recentStartTimestampsEpochMs,
                nowEpochMs = nowEpochMs
            )
            allowed = updatedTimestamps.size <= MAX_STARTS_IN_WINDOW
            prefs[KEY_SIDEBAR_CONFIG_JSON] = serializer.encode(
                current.copy(recentStartTimestampsEpochMs = updatedTimestamps)
            )
        }
        allowed
    }

    suspend fun resetCircuitBreaker() = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            val current = serializer.decode(prefs[KEY_SIDEBAR_CONFIG_JSON])
            prefs[KEY_SIDEBAR_CONFIG_JSON] = serializer.encode(
                current.copy(recentStartTimestampsEpochMs = emptyList())
            )
        }
    }

    companion object {
        val KEY_SIDEBAR_CONFIG_JSON = stringPreferencesKey("sidebar_config_json")
        const val CIRCUIT_BREAKER_WINDOW_MS = 60_000L
        const val MAX_STARTS_IN_WINDOW = 5

        fun evaluateRestartTimestamps(
            existingTimestamps: List<Long>,
            nowEpochMs: Long,
            windowMs: Long = CIRCUIT_BREAKER_WINDOW_MS
        ): List<Long> {
            val cutoff = nowEpochMs - windowMs
            return existingTimestamps.filter { it >= cutoff } + nowEpochMs
        }

        fun isCircuitBreakerTripped(
            existingTimestamps: List<Long>,
            nowEpochMs: Long,
            windowMs: Long = CIRCUIT_BREAKER_WINDOW_MS,
            maxStarts: Int = MAX_STARTS_IN_WINDOW
        ): Boolean {
            return evaluateRestartTimestamps(existingTimestamps, nowEpochMs, windowMs).size > maxStarts
        }
    }
}

