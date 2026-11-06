# SideBar Personal Utility Overlay

* **Author:** Tushar ahmed
* **Date:** 2026-11-06

## Overview

SideBar is a personal, sideloaded Android utility (targeting API 30–35) that provides a persistent edge-anchored overlay handle expanding into a quick-launcher drawer. Built with Jetpack Compose, Kotlin Coroutines/Flow, AndroidX DataStore, and Android `WindowManager`, the app runs via a Foreground Service (`specialUse` type on API 34+). It allows instant access to user-pinned applications and system utilities from any screen without interrupting ongoing workflows.

## Analysis

Seven parallel review lenses evaluated the architecture, operational safety, and system economics of the concept.

### 1. Inventive Contradiction Resolutions & Core Architecture

* **Modality vs. Pass-Through Transparency**: An overlay requires full-screen modal touch handling for outside dismissals and swipe velocity, but full-screen windows block touches to underlying background applications during idle.
  * *Resolution (Dual-Window Segmentation)*: Decouple the overlay into two independent windows:
    1. **Persistent Handle Window**: Sized strictly to the handle (16dp × 96dp), `FLAG_NOT_FOCUSABLE`, anchored to the screen edge.
    2. **Transient Drawer Window**: A full-screen window with a transparent scrim and `FLAG_NOT_TOUCH_MODAL` that is attached to `WindowManager` only when expansion begins (tap or inward swipe exceeding 24dp) and detached immediately upon collapse.
* **Instant Responsiveness vs. Idle Background Overhead**: Rendering Compose inside a background service 24/7 can leak memory and consume battery if StateFlow collectors remain active while the drawer is hidden.
  * *Resolution (Preliminary Action & Deactivation)*: Pre-cache launchable app metadata and icons into an in-memory repository during startup. Bind Compose StateFlow collection to drawer visibility using `SharingStarted.WhileSubscribed(5000)`, ensuring heavy UI composition trees and collectors are completely deactivated when the drawer is collapsed.
* **Edge Drag Sensitivity vs. Android 200dp System Back Gesture Ceiling**: Android 10+ enforces a strict 200dp vertical exclusion limit per screen edge for `View.setSystemGestureExclusionRects`.
  * *Resolution (Dynamic Rect Scoping)*: Restrict gesture exclusion strictly to the 96dp vertical boundary of the handle during idle. Clear exclusion rects (`emptyList()`) during expanded modal states, relying entirely on window touch consumption. Debounce exclusion updates to prevent IPC thrashing during handle drag repositioning.

### 2. Platform Compliance & OS Invariants (Android 14 & 15)

* **Foreground Service `specialUse` Declarations**: On Android 14+ (API 34/35), `startForeground()` with `specialUse` throws a runtime exception if the manifest lacks `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>` and the `FOREGROUND_SERVICE_SPECIAL_USE` permission. Manifest metadata and notification fallback mechanisms (for API 33+) must be explicitly configured.
* **Background Activity Launch (BAL) Restrictions**: Starting applications from an overlay service triggers strict BAL rules. App launches must be dispatched with `FLAG_ACTIVITY_NEW_TASK`, and the drawer must automatically collapse upon launch dispatch with error fallback notifications if an intent fails.
* **Crash-Loop Circuit Breaker**: Under memory pressure or aggressive OEM battery managers, killed services restarted via sticky intents can crash repeatedly. The service will monitor `ActivityManager.getHistoricalProcessExitReasons` and enforce a circuit breaker (tripping if >5 crashes occur within 60 seconds).

### 3. Maintainability, Decoupling & Debuggability

* **Service Monolith Prevention**: Decouple `SidebarService` (process lifecycle container) from `OverlayWindowManager` (Window layout parameters and touch arbitration) and `OverlayLifecycleOwner` (synthetic `LifecycleOwner`, `ViewModelStoreOwner`, and `SavedStateRegistryOwner` bindings for Compose).
* **On-Device Diagnostic Dump**: Implement `Service.dump()` to enable zero-debugger operational inspection via:
  ```bash
  adb shell dumpsys activity service com.imtusharahmed.smartsidebar
  ```
  This dumps window parameters, gesture exclusion rects, overlay coordinates, and the last 50 state transitions from an in-memory ring buffer.
* **Persistence Schema Versioning**: Persist pinned app configurations as a JSON-encoded string containing a schema version and explicit ordering, avoiding raw unordered `stringSetPreferencesKey` migrations.

## Goals vs. Non-Goals

### In-Scope Goals
* **Dual-Window Overlay Architecture**: Ultra-lean persistent handle window + transient modal drawer window.
* **Deterministic Gesture Handling**: Tap or inward swipe (>24dp) expansion, vertical drag repositioning, and dynamic exclusion rect management under the 200dp system limit.
* **Pinned App Drawer**: Grid/list of user-selected launchable apps with icon caching and automatic dismissal upon launch.
* **Configuration Activity (`MainActivity`)**: Runtime overlay permission check (`ACTION_MANAGE_OVERLAY_PERMISSION`), battery optimization guide, service toggle, and package picker with DataStore persistence.
* **Operational Telemetry & Diagnostics**: Intent launch tracing, `Service.dump()` implementation, and crash-loop circuit breaker.

### Out-of-Scope Non-Goals
* **Google Play Store Policy Certification**: No compromises to avoid `QUERY_ALL_PACKAGES` or `SYSTEM_ALERT_WINDOW` review hurdles; distribution is strictly sideloaded/direct APK.
* **Complex Multi-Media Tools**: No screen recording, floating web widgets, or clipboard history managers in v1.
* **Heavy Custom Hardware Toggles**: Avoid `WRITE_SETTINGS` (brightness sliders) or direct camera flashlight manipulation in v1 to prevent hardware lock contention.

## Alternatives Weighed

* **Single Full-Screen Compose Overlay**: Rejected because maintaining an idle full-screen window with touch passthrough either interferes with background apps or causes layout desynchronization. Dual-window architecture cleanly separates idle vs. modal states.
* **Pure Native Views for Overlay**: Weighed by the Skeptical Architect to minimize Compose runtime overhead in background services. Rejected by author in favor of Compose inside WindowManager for rapid UI expressiveness and animation, mitigated by deactivating Compose collection while collapsed.
* **Unordered `stringSetPreferencesKey` in DataStore**: Rejected because it cannot preserve user-defined app order. Replaced with versioned JSON serialization.
* **Google Play Store Distribution**: Rejected because Google Play prohibits `QUERY_ALL_PACKAGES` and restricts `FOREGROUND_SERVICE_SPECIAL_USE` for general utility launchers.

## Key Decisions & Staging for Next Steps

1. **Architecture Model**: Dual-window management (`OverlayWindowManager`) hosting Compose via an isolated `OverlayLifecycleOwner`.
2. **Gesture Specification**: 96dp handle with system exclusion; vertical repositioning on drag, horizontal expansion on swipe/tap, outside-touch dismissal via transparent scrim window.
3. **Service Layer**: Android 14+ compliant Foreground Service with `specialUse` subtype property, notification channel setup, and `Service.dump()` diagnostic facility.
4. **Readiness for Specification (`/egm-draft`)**: The architecture is validated and ready for formal technical specification drafting.

