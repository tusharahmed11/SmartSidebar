package com.imtusharahmed.smartsidebar.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imtusharahmed.smartsidebar.diagnostics.DiagnosticRingBuffer
import com.imtusharahmed.smartsidebar.model.AppShortcut
import com.imtusharahmed.smartsidebar.model.PersistedSidebarConfig
import com.imtusharahmed.smartsidebar.ui.overlay.SidebarDrawer
import com.imtusharahmed.smartsidebar.ui.overlay.SidebarHandle
import com.imtusharahmed.smartsidebar.ui.theme.SmartSidebarTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class SafeScreenBounds(
    val minY: Int,
    val maxY: Int,
    val screenHeightPx: Int,
    val topInsetPx: Int,
    val bottomInsetPx: Int
)

class OverlayWindowManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val ringBuffer: DiagnosticRingBuffer,
    private val pinnedShortcutsFlow: StateFlow<List<AppShortcut>>,
    private val onLaunchPackage: (String) -> Unit,
    private val onOpenMainActivity: () -> Unit
) {
    private val handleLifecycleOwner = OverlayLifecycleOwner()
    private val drawerLifecycleOwner = OverlayLifecycleOwner()

    private var handleView: ComposeView? = null
    private var drawerView: ComposeView? = null

    private var handleParams: WindowManager.LayoutParams? = null
    private var drawerParams: WindowManager.LayoutParams? = null

    private var currentHandleY: Int = PersistedSidebarConfig.DEFAULT_HANDLE_Y_OFFSET_PX
    private val handleYMutableFlow = MutableStateFlow(currentHandleY)
    val handleYFlow: StateFlow<Int> = handleYMutableFlow.asStateFlow()

    private var isDrawerAttached: Boolean = false
    private var lastAppliedExclusionRect: Rect? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val density: Float
        get() = context.resources.displayMetrics.density

    val handleWidthPx: Int
        get() = (HANDLE_WIDTH_DP * density).roundToInt()

    val handleHeightPx: Int
        get() = (HANDLE_HEIGHT_DP * density).roundToInt()

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    fun getSafeBounds(viewHeightPx: Int, marginDp: Int = DEFAULT_MARGIN_DP): SafeScreenBounds {
        val marginPx = (marginDp * density).roundToInt()
        val screenHeight: Int
        val topInset: Int
        val bottomInset: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screenHeight = metrics.bounds.height()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            topInset = insets.top
            bottomInset = insets.bottom
        } else {
            screenHeight = context.resources.displayMetrics.heightPixels
            val statusRes = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            topInset = if (statusRes > 0) context.resources.getDimensionPixelSize(statusRes) else (24 * density).roundToInt()
            val navRes = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            bottomInset = if (navRes > 0) context.resources.getDimensionPixelSize(navRes) else (48 * density).roundToInt()
        }

        val minY = topInset + marginPx
        val maxY = (screenHeight - bottomInset - viewHeightPx - marginPx).coerceAtLeast(minY)

        return SafeScreenBounds(
            minY = minY,
            maxY = maxY,
            screenHeightPx = screenHeight,
            topInsetPx = topInset,
            bottomInsetPx = bottomInset
        )
    }

    fun attachHandle(initialYOffsetPx: Int) {
        if (handleView != null) return

        val bounds = getSafeBounds(viewHeightPx = handleHeightPx)
        currentHandleY = clampCoordinate(
            yPx = initialYOffsetPx,
            screenHeightPx = bounds.screenHeightPx,
            viewHeightPx = handleHeightPx,
            topInsetPx = bounds.topInsetPx,
            bottomInsetPx = bounds.bottomInsetPx,
            marginPx = (DEFAULT_MARGIN_DP * density).roundToInt()
        )
        handleYMutableFlow.value = currentHandleY

        val params = WindowManager.LayoutParams(
            handleWidthPx,
            handleHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = currentHandleY
        }
        handleParams = params

        val view = ComposeView(context).apply {
            handleLifecycleOwner.attachToComposeView(this)
            setContent {
                SmartSidebarTheme {
                    SidebarHandle(
                        isRepositioningEnabled = false,
                        onOpenDrawer = { openDrawer() }
                    )
                }
            }
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (!isDrawerAttached) {
                    applyHandleGestureExclusion(v)
                }
            }
        }

        runCatching {
            handleLifecycleOwner.moveToState(Lifecycle.State.RESUMED)
            windowManager.addView(view, params)
            handleView = view
            applyHandleGestureExclusion(view)
            ringBuffer.record(
                "WINDOW_ATTACH",
                "Attached handle window w=${params.width} h=${params.height} y=${params.y}"
            )
        }.onFailure { error ->
            ringBuffer.record("WINDOW_ERROR", "Failed to attach handle: ${error.message}")
        }
    }

    fun setHandleYFromPersistence(savedY: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setHandleYFromPersistence(savedY) }
            return
        }
        val bounds = getSafeBounds(viewHeightPx = handleHeightPx)
        val clamped = clampCoordinate(
            yPx = savedY,
            screenHeightPx = bounds.screenHeightPx,
            viewHeightPx = handleHeightPx,
            topInsetPx = bounds.topInsetPx,
            bottomInsetPx = bounds.bottomInsetPx,
            marginPx = (DEFAULT_MARGIN_DP * density).roundToInt()
        )
        if (clamped == currentHandleY) return
        currentHandleY = clamped
        handleYMutableFlow.value = currentHandleY
        val view = handleView ?: return
        val params = handleParams ?: return
        params.y = currentHandleY
        runCatching {
            windowManager.updateViewLayout(view, params)
            if (!isDrawerAttached) {
                applyHandleGestureExclusion(view)
            }
        }
    }

    fun openDrawer() {
        if (isDrawerAttached) return

        // Clear handle gesture exclusion while modal drawer is expanded
        handleView?.let { ViewCompat.setSystemGestureExclusionRects(it, emptyList()) }
        lastAppliedExclusionRect = null

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.FILL
        }
        drawerParams = params

        val view = ComposeView(context).apply {
            setOnTouchListener { _, event ->
                if (event?.action == MotionEvent.ACTION_OUTSIDE) {
                    closeDrawer()
                    true
                } else {
                    false
                }
            }
            drawerLifecycleOwner.attachToComposeView(this)
            setContent {
                val shortcuts by pinnedShortcutsFlow.collectAsStateWithLifecycle()
                val handleY by handleYFlow.collectAsStateWithLifecycle()
                val bounds = getSafeBounds(viewHeightPx = handleHeightPx)
                val marginPx = (DEFAULT_MARGIN_DP * density).roundToInt()

                SmartSidebarTheme {
                    SidebarDrawer(
                        pinnedApps = shortcuts,
                        handleYOffsetPx = handleY,
                        isRepositioningEnabled = false,
                        minSafeYPx = bounds.minY,
                        maxSafeYPx = bounds.screenHeightPx - bounds.bottomInsetPx - marginPx,
                        onAppClick = { packageName ->
                            closeDrawer()
                            onLaunchPackage(packageName)
                        },
                        onEditShortcutsClick = {
                            closeDrawer()
                            onOpenMainActivity()
                        },
                        onDismiss = { closeDrawer() }
                    )
                }
            }
        }

        runCatching {
            drawerLifecycleOwner.moveToState(Lifecycle.State.RESUMED)
            windowManager.addView(view, params)
            drawerView = view
            isDrawerAttached = true
            ringBuffer.record("DRAWER_OPEN", "Attached transient drawer window")
        }.onFailure { error ->
            ringBuffer.record("DRAWER_ERROR", "Failed to attach drawer: ${error.message}")
        }
    }

    fun closeDrawer() {
        if (!isDrawerAttached) return
        val view = drawerView
        drawerView = null
        drawerParams = null
        isDrawerAttached = false

        if (view != null) {
            runCatching {
                drawerLifecycleOwner.moveToState(Lifecycle.State.CREATED)
                windowManager.removeViewImmediate(view)
                ringBuffer.record("DRAWER_CLOSE", "Removed transient drawer window")
            }.onFailure { error ->
                ringBuffer.record("DRAWER_ERROR", "removeView failed: ${error.message}")
            }
        }

        handleView?.let { applyHandleGestureExclusion(it) }
    }

    fun setHandleVisibility(visible: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setHandleVisibility(visible) }
            return
        }
        val view = handleView ?: return
        val targetVisibility = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != targetVisibility) {
            view.visibility = targetVisibility
            if (visible) {
                if (!isDrawerAttached) {
                    applyHandleGestureExclusion(view)
                }
                ringBuffer.record("HANDLE_VISIBILITY", "Handle shown")
            } else {
                ViewCompat.setSystemGestureExclusionRects(view, emptyList())
                lastAppliedExclusionRect = null
                ringBuffer.record("HANDLE_VISIBILITY", "Handle hidden")
            }
        }
    }

    fun onConfigurationChanged() {
        val bounds = getSafeBounds(viewHeightPx = handleHeightPx)
        val clamped = clampCoordinate(
            yPx = currentHandleY,
            screenHeightPx = bounds.screenHeightPx,
            viewHeightPx = handleHeightPx,
            topInsetPx = bounds.topInsetPx,
            bottomInsetPx = bounds.bottomInsetPx,
            marginPx = (DEFAULT_MARGIN_DP * density).roundToInt()
        )
        if (clamped != currentHandleY) {
            ringBuffer.record(
                "BOUNDS_CLAMPED",
                "Clamped handle Y from $currentHandleY to $clamped on config change (screenHeight=${bounds.screenHeightPx} top=${bounds.topInsetPx} bot=${bounds.bottomInsetPx})"
            )
            currentHandleY = clamped
            handleYMutableFlow.value = currentHandleY
        }
        val view = handleView ?: return
        val params = handleParams ?: return
        params.width = handleWidthPx
        params.height = handleHeightPx
        params.y = currentHandleY
        runCatching {
            windowManager.updateViewLayout(view, params)
            if (!isDrawerAttached) {
                applyHandleGestureExclusion(view)
            }
        }
    }

    fun destroyAll() {
        closeDrawer()
        drawerLifecycleOwner.clear()

        val hView = handleView
        handleView = null
        handleParams = null
        if (hView != null) {
            runCatching {
                ViewCompat.setSystemGestureExclusionRects(hView, emptyList())
                windowManager.removeViewImmediate(hView)
            }
        }
        handleLifecycleOwner.clear()
        ringBuffer.record("OVERLAY_DESTROY", "Destroyed all overlay windows and lifecycle owners")
    }

    private fun applyHandleGestureExclusion(view: View) {
        val w = view.width.takeIf { it > 0 } ?: handleWidthPx
        val h = view.height.takeIf { it > 0 } ?: handleHeightPx
        val maxAllowedHeightPx = (MAX_SYSTEM_EXCLUSION_DP * density).roundToInt()
        val safeHeight = h.coerceAtMost(maxAllowedHeightPx)
        val rect = Rect(0, 0, w, safeHeight)
        ViewCompat.setSystemGestureExclusionRects(view, listOf(rect))
        lastAppliedExclusionRect = rect
    }



    fun buildDiagnosticSummary(): String {
        val bounds = getSafeBounds(viewHeightPx = handleHeightPx)
        return buildString {
            appendLine("Overlay State: ${if (isDrawerAttached) "EXPANDED" else "COLLAPSED"}")
            appendLine("Handle Lifecycle: ${handleLifecycleOwner.lifecycle.currentState}")
            appendLine("Drawer Lifecycle: ${drawerLifecycleOwner.lifecycle.currentState}")
            appendLine("Handle Params: x=0 y=$currentHandleY w=$handleWidthPx h=$handleHeightPx (96dp) screenH=${bounds.screenHeightPx}")
            appendLine("Safe Screen Insets: top=${bounds.topInsetPx} bottom=${bounds.bottomInsetPx} minY=${bounds.minY} maxY=${bounds.maxY}")
            appendLine("Active Gesture Exclusion Rect: ${lastAppliedExclusionRect ?: "NONE (released)"}")
            appendLine("System Exclusion Quota: ${HANDLE_HEIGHT_DP}dp / ${MAX_SYSTEM_EXCLUSION_DP}dp")
        }
    }

    companion object {
        const val HANDLE_WIDTH_DP = 16
        const val HANDLE_HEIGHT_DP = 96
        const val MAX_SYSTEM_EXCLUSION_DP = 200
        const val DEFAULT_MARGIN_DP = 8

        fun clampCoordinate(
            yPx: Int,
            screenHeightPx: Int,
            viewHeightPx: Int,
            topInsetPx: Int = 0,
            bottomInsetPx: Int = 0,
            marginPx: Int = 0
        ): Int {
            val minY = topInsetPx + marginPx
            val maxY = (screenHeightPx - bottomInsetPx - viewHeightPx - marginPx).coerceAtLeast(minY)
            return yPx.coerceIn(minY, maxY)
        }

        fun clampCoordinate(yPx: Int, screenHeightPx: Int, viewHeightPx: Int): Int {
            return clampCoordinate(yPx, screenHeightPx, viewHeightPx, 0, 0, 0)
        }
    }
}
