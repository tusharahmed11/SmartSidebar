package com.imtusharahmed.smartsidebar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imtusharahmed.smartsidebar.model.AppShortcut
import com.imtusharahmed.smartsidebar.ui.config.ConfigUiState
import com.imtusharahmed.smartsidebar.ui.config.SidebarConfigViewModel
import com.imtusharahmed.smartsidebar.ui.overlay.toImageBitmapOrNull
import com.imtusharahmed.smartsidebar.ui.theme.SmartSidebarTheme
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {

    private var hasOverlayPermission by mutableStateOf(false)
    private var isIgnoringBatteryOptimizations by mutableStateOf(true)
    private var sidebarViewModel: SidebarConfigViewModel? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Service starts regardless of notification permission on API 33+ */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePermissionStates()
        enableEdgeToEdge()
        setContent {
            SmartSidebarTheme {
                val viewModel: SidebarConfigViewModel = koinViewModel()
                sidebarViewModel = viewModel
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val context = LocalContext.current

                SidebarMainScreen(
                    hasOverlayPermission = hasOverlayPermission,
                    isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                    uiState = uiState,
                    onRequestOverlayPermission = { openOverlayPermissionSettings() },
                    onRequestBatteryOptimization = { openBatteryOptimizationSettings() },
                    onToggleService = { enabled ->
                        if (enabled) {
                            requestNotificationPermissionIfNeeded()
                        }
                        viewModel.onSetServiceEnabled(context, enabled)
                    },
                    onToggleRepositioning = viewModel::onToggleRepositioning,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                    onToggleAppPinned = viewModel::onToggleAppPinned
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
        if (hasOverlayPermission) {
            sidebarViewModel?.ensureServiceStartedIfEnabled(this)
        }
    }

    private fun updatePermissionStates() {
        hasOverlayPermission = Settings.canDrawOverlays(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isIgnoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }.onFailure {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            runCatching { startActivity(fallbackIntent) }
        }
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching {
            startActivity(intent)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarMainScreen(
    hasOverlayPermission: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    uiState: ConfigUiState,
    onRequestOverlayPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onToggleService: (Boolean) -> Unit,
    onToggleRepositioning: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onToggleAppPinned: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.app_name))
                }
            )
        }
    ) { innerPadding ->
        if (!hasOverlayPermission) {
            OverlayPermissionOnboarding(
                onGrantClick = onRequestOverlayPermission,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                ServiceControlCard(
                    isRunning = uiState.isServiceRunning,
                    pinnedCount = uiState.pinnedCount,
                    onToggleService = onToggleService
                )

                Spacer(modifier = Modifier.height(10.dp))

                RepositioningControlCard(
                    isRepositioningEnabled = uiState.isRepositioningEnabled,
                    onToggleRepositioning = onToggleRepositioning
                )

                if (!isIgnoringBatteryOptimizations) {
                    Spacer(modifier = Modifier.height(10.dp))
                    BatteryOptimizationCard(
                        onOptimizeClick = onRequestBatteryOptimization
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(text = stringResource(R.string.search_apps_placeholder))
                    },
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = uiState.apps,
                        key = { it.packageName }
                    ) { shortcut ->
                        AppPickerRow(
                            shortcut = shortcut,
                            onCheckedChange = { checked ->
                                onToggleAppPinned(shortcut.packageName, checked)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayPermissionOnboarding(
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.permission_overlay_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.permission_overlay_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onGrantClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.permission_grant_button))
                }
            }
        }
    }
}

@Composable
private fun ServiceControlCard(
    isRunning: Boolean,
    pinnedCount: Int,
    onToggleService: (Boolean) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.service_toggle_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                val statusSubtitle = if (isRunning) {
                    stringResource(R.string.service_toggle_subtitle_running)
                } else {
                    stringResource(R.string.service_toggle_subtitle_stopped)
                }
                Text(
                    text = "$statusSubtitle • $pinnedCount pinned",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = isRunning,
                onCheckedChange = onToggleService
            )
        }
    }
}

@Composable
private fun RepositioningControlCard(
    isRepositioningEnabled: Boolean,
    onToggleRepositioning: (Boolean) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.repositioning_toggle_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                val subtitle = if (isRepositioningEnabled) {
                    stringResource(R.string.repositioning_toggle_subtitle_enabled)
                } else {
                    stringResource(R.string.repositioning_toggle_subtitle_disabled)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = isRepositioningEnabled,
                onCheckedChange = onToggleRepositioning
            )
        }
    }
}

@Composable
private fun BatteryOptimizationCard(
    onOptimizeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.battery_optimization_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.battery_optimization_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onOptimizeClick,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = stringResource(R.string.battery_optimization_button))
            }
        }
    }
}

@Composable
private fun AppPickerRow(
    shortcut: AppShortcut,
    onCheckedChange: (Boolean) -> Unit
) {
    val iconBitmap: ImageBitmap? = remember(shortcut.packageName, shortcut.icon) {
        shortcut.icon?.toImageBitmapOrNull(sizePx = 128)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!shortcut.isPinned) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = shortcut.label,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = shortcut.label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortcut.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = shortcut.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Checkbox(
            checked = shortcut.isPinned,
            onCheckedChange = onCheckedChange
        )
    }
}
