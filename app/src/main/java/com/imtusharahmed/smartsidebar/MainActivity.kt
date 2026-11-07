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
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.imtusharahmed.smartsidebar.overlay.OverlayWindowManager
import com.imtusharahmed.smartsidebar.service.ForegroundAppDetector
import com.imtusharahmed.smartsidebar.ui.config.ConfigUiState
import com.imtusharahmed.smartsidebar.ui.config.SidebarConfigViewModel
import com.imtusharahmed.smartsidebar.ui.overlay.toImageBitmapOrNull
import com.imtusharahmed.smartsidebar.ui.theme.SmartSidebarTheme
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

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
                    onSetHandleYPosition = viewModel::onSetHandleYPosition,
                    onToggleHideWhenAppsRunning = viewModel::onToggleHideWhenAppsRunning,
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
    onSetHandleYPosition: (Int) -> Unit,
    onToggleHideWhenAppsRunning: (Boolean) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onToggleAppPinned: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showPositionDialog by remember { mutableStateOf(false) }
    var showUsagePermissionDialog by remember { mutableStateOf(false) }

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

                SidebarPositionCard(
                    onChangePositionClick = { showPositionDialog = true }
                )

                Spacer(modifier = Modifier.height(10.dp))

                HideWhenAppsRunningCard(
                    hideWhenAppsRunning = uiState.hideWhenAppsRunning,
                    onToggleHideWhenAppsRunning = { enabled: Boolean ->
                        if (enabled) {
                            val detector = ForegroundAppDetector(context)
                            if (!detector.hasUsageStatsPermission()) {
                                showUsagePermissionDialog = true
                            } else {
                                onToggleHideWhenAppsRunning(true)
                            }
                        } else {
                            onToggleHideWhenAppsRunning(false)
                        }
                    }
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

    if (showPositionDialog) {
        ChangePositionDialog(
            currentY = uiState.handleYOffsetPx,
            onPositionChanged = onSetHandleYPosition,
            onDismiss = { showPositionDialog = false }
        )
    }

    if (showUsagePermissionDialog) {
        UsageAccessPermissionDialog(
            onGrant = {
                showUsagePermissionDialog = false
                ForegroundAppDetector.openUsageAccessSettings(context)
            },
            onDismiss = { showUsagePermissionDialog = false }
        )
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
private fun SidebarPositionCard(
    onChangePositionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
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
                    text = stringResource(R.string.position_card_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.position_card_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onChangePositionClick,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = stringResource(R.string.change_position_button))
            }
        }
    }
}

@Composable
private fun HideWhenAppsRunningCard(
    hideWhenAppsRunning: Boolean,
    onToggleHideWhenAppsRunning: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
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
                    text = stringResource(R.string.hide_apps_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.hide_apps_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = hideWhenAppsRunning,
                onCheckedChange = onToggleHideWhenAppsRunning
            )
        }
    }
}

@SuppressLint("InternalInsetResource", "DiscouragedApi")
@Composable
private fun ChangePositionDialog(
    currentY: Int,
    onPositionChanged: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val handleHeightPx = (OverlayWindowManager.HANDLE_HEIGHT_DP * density).roundToInt()
    val marginPx = (OverlayWindowManager.DEFAULT_MARGIN_DP * density).roundToInt()

    val (minY, maxY) = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val min = insets.top + marginPx
            val max = (metrics.bounds.height() - insets.bottom - handleHeightPx - marginPx).coerceAtLeast(min)
            min to max
        } else {
            val screenHeight = context.resources.displayMetrics.heightPixels
            val statusRes = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val top = if (statusRes > 0) context.resources.getDimensionPixelSize(statusRes) else (24 * density).roundToInt()
            val navRes = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            val bottom = if (navRes > 0) context.resources.getDimensionPixelSize(navRes) else (48 * density).roundToInt()
            val min = top + marginPx
            val max = (screenHeight - bottom - handleHeightPx - marginPx).coerceAtLeast(min)
            min to max
        }
    }

    val originalY = remember { currentY }
    var sliderValue by remember {
        val clamped = currentY.coerceIn(minY, maxY)
        mutableFloatStateOf(clamped.toFloat())
    }

    AlertDialog(
        onDismissRequest = {
            onPositionChanged(originalY)
            onDismiss()
        },
        title = {
            Text(
                text = stringResource(R.string.change_position_dialog_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.change_position_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                val progress = if (maxY > minY) {
                    ((sliderValue - minY) / (maxY - minY)).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
                val percent = (progress * 100).roundToInt()

                PhonePositionPreview(
                    progress = progress,
                    modifier = Modifier
                        .width(68.dp)
                        .height(104.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Vertical Position",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = sliderValue,
                    onValueChange = { newValue ->
                        sliderValue = newValue
                        onPositionChanged(newValue.roundToInt())
                    },
                    valueRange = minY.toFloat()..maxY.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AssistChip(
                        onClick = {
                            sliderValue = minY.toFloat()
                            onPositionChanged(minY)
                        },
                        label = { Text(text = stringResource(R.string.position_top)) }
                    )
                    AssistChip(
                        onClick = {
                            val mid = (minY + maxY) / 2
                            sliderValue = mid.toFloat()
                            onPositionChanged(mid)
                        },
                        label = { Text(text = stringResource(R.string.position_center)) }
                    )
                    AssistChip(
                        onClick = {
                            sliderValue = maxY.toFloat()
                            onPositionChanged(maxY)
                        },
                        label = { Text(text = stringResource(R.string.position_bottom)) }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onPositionChanged(sliderValue.roundToInt())
                    onDismiss()
                }
            ) {
                Text(text = stringResource(R.string.save_position_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onPositionChanged(originalY)
                    onDismiss()
                }
            ) {
                Text(text = stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
private fun PhonePositionPreview(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 6.dp)
        ) {
            val handleHeight = 22.dp
            val availableHeight = maxHeight - handleHeight
            val offsetY = availableHeight * progress.coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = offsetY)
                    .width(4.dp)
                    .height(handleHeight)
                    .clip(RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun UsageAccessPermissionDialog(
    onGrant: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.usage_permission_dialog_title))
        },
        text = {
            Text(
                text = stringResource(R.string.usage_permission_dialog_message),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onGrant) {
                Text(text = stringResource(R.string.grant_permission_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel_button))
            }
        }
    )
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
