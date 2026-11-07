package com.imtusharahmed.smartsidebar.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun SidebarHandle(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    isRepositioningEnabled: Boolean = false,
    onVerticalDragDelta: (Float) -> Unit = {},
    onVerticalDragEnd: () -> Unit = {}
) {
    val density = LocalDensity.current
    val swipeThresholdPx = remember(density) { with(density) { 24.dp.toPx() } }
    val swipeVelocityThresholdPxPerSec = remember(density) { with(density) { 500.dp.toPx() } }

    var accumulatedHorizontalPx by remember { mutableFloatStateOf(0f) }

    val handleShape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)

    Box(
        modifier = modifier
            .width(16.dp)
            .height(96.dp)
            .clip(handleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onOpenDrawer() }
                )
            }
            .pointerInput(swipeThresholdPx, swipeVelocityThresholdPxPerSec) {
                val velocityTracker = VelocityTracker()
                var openedInThisGesture = false

                detectHorizontalDragGestures(
                    onDragStart = {
                        accumulatedHorizontalPx = 0f
                        openedInThisGesture = false
                        velocityTracker.resetTracking()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        accumulatedHorizontalPx += dragAmount
                        if (!openedInThisGesture && accumulatedHorizontalPx <= -swipeThresholdPx) {
                            openedInThisGesture = true
                            onOpenDrawer()
                        }
                    },
                    onDragEnd = {
                        if (!openedInThisGesture) {
                            val velocityX = velocityTracker.calculateVelocity().x
                            if (velocityX <= -swipeVelocityThresholdPxPerSec ||
                                abs(accumulatedHorizontalPx) >= swipeThresholdPx
                            ) {
                                onOpenDrawer()
                            }
                        }
                        accumulatedHorizontalPx = 0f
                        openedInThisGesture = false
                    },
                    onDragCancel = {
                        accumulatedHorizontalPx = 0f
                        openedInThisGesture = false
                    }
                )
            }
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
                .width(4.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f))
        )
    }
}

