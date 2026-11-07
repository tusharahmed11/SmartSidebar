package com.imtusharahmed.smartsidebar

import com.imtusharahmed.smartsidebar.data.AppRepository
import com.imtusharahmed.smartsidebar.data.SidebarConfigSerializer
import com.imtusharahmed.smartsidebar.diagnostics.DiagnosticRingBuffer
import com.imtusharahmed.smartsidebar.model.PersistedSidebarConfig
import com.imtusharahmed.smartsidebar.overlay.OverlayWindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    private val serializer = SidebarConfigSerializer()

    @Test
    fun serializer_roundTrip_preservesPinnedPackageOrderAndHandleY() {
        val original = PersistedSidebarConfig(
            schemaVersion = 2,
            pinnedPackages = listOf("com.pkg.zebra", "com.pkg.alpha", "com.pkg.middle"),
            handleYOffsetPx = 640,
            isRepositioningEnabled = false,
            isServiceEnabled = true,
            recentStartTimestampsEpochMs = listOf(1000L, 2000L, 3000L),
            hideWhenAppsRunning = true
        )

        val encoded = serializer.encode(original)
        val decoded = serializer.decode(encoded)

        assertEquals(2, decoded.schemaVersion)
        assertEquals(listOf("com.pkg.zebra", "com.pkg.alpha", "com.pkg.middle"), decoded.pinnedPackages)
        assertEquals(640, decoded.handleYOffsetPx)
        assertFalse(decoded.isRepositioningEnabled)
        assertTrue(decoded.isServiceEnabled)
        assertEquals(listOf(1000L, 2000L, 3000L), decoded.recentStartTimestampsEpochMs)
        assertTrue(decoded.hideWhenAppsRunning)
    }

    @Test
    fun serializer_backwardCompatibility_defaultsRepositioningToFalse() {
        val legacyJson = """
            {
                "schema_version": 1,
                "handle_y_offset_px": 500,
                "pinned_packages": ["com.example.app"]
            }
        """.trimIndent()

        val decoded = serializer.decode(legacyJson)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(500, decoded.handleYOffsetPx)
        assertFalse(decoded.isRepositioningEnabled)
        assertFalse(decoded.isServiceEnabled)
        assertFalse(decoded.hideWhenAppsRunning)
        assertEquals(listOf("com.example.app"), decoded.pinnedPackages)
    }

    @Test
    fun serializer_malformedOrNullJson_returnsSafeDefaults() {
        val fromNull = serializer.decode(null)
        val fromGarbage = serializer.decode("{not-valid-json")

        assertEquals(PersistedSidebarConfig(), fromNull)
        assertEquals(PersistedSidebarConfig(), fromGarbage)
    }

    @Test
    fun diagnosticRingBuffer_evictsOldestEventsWhenCapacityExceeded() {
        val buffer = DiagnosticRingBuffer(maxCapacity = 3)
        buffer.record("TEST", "event-1", timestampEpochMs = 10L)
        buffer.record("TEST", "event-2", timestampEpochMs = 20L)
        buffer.record("TEST", "event-3", timestampEpochMs = 30L)
        buffer.record("TEST", "event-4", timestampEpochMs = 40L)

        val snapshot = buffer.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals("event-2", snapshot[0].message)
        assertEquals("event-3", snapshot[1].message)
        assertEquals("event-4", snapshot[2].message)
    }

    @Test
    fun circuitBreaker_tripsOnlyWhenExceedingMaxStartsWithinWindow() {
        val baseTime = 100_000L
        val fiveStarts = listOf(
            baseTime + 1_000L,
            baseTime + 5_000L,
            baseTime + 10_000L,
            baseTime + 20_000L,
            baseTime + 30_000L
        )

        // 5th start (4 existing + 1 new = 5) is still within budget
        assertFalse(
            AppRepository.isCircuitBreakerTripped(
                existingTimestamps = fiveStarts.take(4),
                nowEpochMs = baseTime + 30_000L
            )
        )

        // 6th start within 60s trips the circuit breaker
        assertTrue(
            AppRepository.isCircuitBreakerTripped(
                existingTimestamps = fiveStarts,
                nowEpochMs = baseTime + 40_000L
            )
        )

        // After 60s window expires, old starts are pruned and circuit breaker does not trip
        assertFalse(
            AppRepository.isCircuitBreakerTripped(
                existingTimestamps = fiveStarts,
                nowEpochMs = baseTime + 95_000L
            )
        )
    }

    @Test
    fun clampCoordinate_constrainsWithinPortraitAndLandscapeBounds() {
        val handleHeight = 288 // 96dp @ 3x density
        val portraitHeight = 2400
        val landscapeHeight = 1080

        assertEquals(0, OverlayWindowManager.clampCoordinate(-150, portraitHeight, handleHeight))
        assertEquals(500, OverlayWindowManager.clampCoordinate(500, portraitHeight, handleHeight))
        assertEquals(
            portraitHeight - handleHeight,
            OverlayWindowManager.clampCoordinate(3000, portraitHeight, handleHeight)
        )
        assertEquals(
            landscapeHeight - handleHeight,
            OverlayWindowManager.clampCoordinate(2100, landscapeHeight, handleHeight)
        )
    }

    @Test
    fun clampCoordinate_withSystemInsetsAndMargins_neverGoesBelowScreenOrBehindNavBar() {
        val handleHeight = 288
        val screenHeight = 2400
        val topInset = 120 // e.g. status bar + camera notch
        val bottomInset = 144 // e.g. 3-button or gesture nav bar
        val margin = 24 // 8dp margin

        val minY = topInset + margin // 144
        val maxY = screenHeight - bottomInset - handleHeight - margin // 2400 - 144 - 288 - 24 = 1944

        // Attempting to drag past top boundary stops at minY
        assertEquals(
            minY,
            OverlayWindowManager.clampCoordinate(
                yPx = 50,
                screenHeightPx = screenHeight,
                viewHeightPx = handleHeight,
                topInsetPx = topInset,
                bottomInsetPx = bottomInset,
                marginPx = margin
            )
        )

        // Attempting to drag past bottom of screen stops at maxY
        val clampedBottom = OverlayWindowManager.clampCoordinate(
            yPx = 3000,
            screenHeightPx = screenHeight,
            viewHeightPx = handleHeight,
            topInsetPx = topInset,
            bottomInsetPx = bottomInset,
            marginPx = margin
        )
        assertEquals(maxY, clampedBottom)

        // Ensure bottom of view never reaches navigation bar or below screen
        assertTrue(clampedBottom + handleHeight <= screenHeight - bottomInset)
    }
}
