package com.imtusharahmed.smartsidebar.ui.overlay

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.imtusharahmed.smartsidebar.R
import com.imtusharahmed.smartsidebar.model.AppShortcut

@Composable
fun SidebarDrawer(
    pinnedApps: List<AppShortcut>,
    onAppClick: (String) -> Unit,
    onEditShortcutsClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    handleYOffsetPx: Int = 300,
    isRepositioningEnabled: Boolean = false,
    minSafeYPx: Int = 0,
    maxSafeYPx: Int = Int.MAX_VALUE,
    onVerticalDragDelta: (Float) -> Unit = {},
    onVerticalDragEnd: () -> Unit = {}
) {
    var visible by remember { mutableStateOf(false) }
    var cardHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        visible = true
    }

    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    val rawCardY = handleYOffsetPx
    val clampedCardY = if (cardHeightPx > 0) {
        val maxBound = (maxSafeYPx - cardHeightPx).coerceAtLeast(minSafeYPx)
        rawCardY.coerceIn(minSafeYPx, maxBound)
    } else {
        rawCardY.coerceIn(minSafeYPx, maxSafeYPx)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.2f))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.TopEnd
    ) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.offset { IntOffset(0, clampedCardY) },
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(durationMillis = 180)
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 140)
            ) + fadeOut(animationSpec = tween(durationMillis = 140))
        ) {
            ElevatedCard(
                modifier = Modifier
                    .width(200.dp)
                    .heightIn(min = 160.dp, max = 480.dp)
                    .padding(end = 8.dp)
                    .onSizeChanged { cardHeightPx = it.height }
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, bottom = 6.dp)
                            .then(
                                if (isRepositioningEnabled) {
                                    Modifier.pointerInput(Unit) {
                                        detectVerticalDragGestures(
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                onVerticalDragDelta(dragAmount)
                                            },
                                            onDragEnd = { onVerticalDragEnd() },
                                            onDragCancel = { onVerticalDragEnd() }
                                        )
                                    }
                                } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (isRepositioningEnabled) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                    }
                                )
                        )
                    }
                    if (pinnedApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp, horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.drawer_empty_state),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = pinnedApps,
                                key = { it.packageName }
                            ) { shortcut ->
                                ShortcutGridItem(
                                    shortcut = shortcut,
                                    onClick = { onAppClick(shortcut.packageName) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    TextButton(
                        onClick = onEditShortcutsClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.edit_shortcuts_button),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutGridItem(
    shortcut: AppShortcut,
    onClick: () -> Unit
) {
    val iconBitmap: ImageBitmap? = remember(shortcut.packageName, shortcut.icon) {
        shortcut.icon?.toImageBitmapOrNull(sizePx = 128)
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = shortcut.label,
                modifier = Modifier.size(44.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = shortcut.label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = shortcut.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

fun Drawable.toImageBitmapOrNull(sizePx: Int = 128): ImageBitmap? {
    return runCatching {
        val width = if (intrinsicWidth > 0) intrinsicWidth else sizePx
        val height = if (intrinsicHeight > 0) intrinsicHeight else sizePx
        toBitmap(width = width.coerceAtMost(sizePx), height = height.coerceAtMost(sizePx)).asImageBitmap()
    }.getOrNull()
}

