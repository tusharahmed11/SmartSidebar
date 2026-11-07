# Smart SideBar

Smart SideBar is a high-performance, edge-anchored application launcher overlay for Android (API 30–35). Built entirely with **Jetpack Compose**, **Kotlin Coroutines/Flow**, **Koin**, and Android's native **`WindowManager`**, it provides instant access to your favorite pinned applications from any screen with zero workflow interruption.

---

## Table of Contents
- [How It Works](#how-it-works)
  - [Dual-Window Architecture](#1-dual-window-architecture)
  - [Synthetic Compose Hosting (`OverlayLifecycleOwner`)](#2-synthetic-compose-hosting-overlaylifecycleowner)
  - [System Gesture Exclusion Management](#3-system-gesture-exclusion-management)
  - [Thread Concurrency & Safety](#4-thread-concurrency--safety)
  - [Crash-Loop Circuit Breaker & Lifecycle Resilience](#5-crash-loop-circuit-breaker--lifecycle-resilience)
- [What, Which & Why (Tech Stack & Design Decisions)](#what-which--why-tech-stack--design-decisions)
- [How to Customize](#how-to-customize)
  - [1. Handle Size, Shape & Visual Style](#1-handle-size-shape--visual-style)
  - [2. Drawer Card Layout & Grid Dimensions](#2-drawer-card-layout--grid-dimensions)
  - [3. Position Range & Safe Insets](#3-position-range--safe-insets)
  - [4. Adding Custom Quick Actions or Tools](#4-adding-custom-quick-actions-or-tools)
- [Diagnostics & Debugging](#diagnostics--debugging)
- [Permissions & Requirements](#permissions--requirements)
- [Building & Installing](#building--installing)

---

## How It Works

### 1. Dual-Window Architecture
Overlay applications face a classic contradiction: full-screen touch interception is required to detect outside taps and swipe velocities, but keeping a full-screen window active when idle blocks touches to underlying apps.

Smart SideBar solves this through **Dual-Window Segmentation**:
```
+-------------------------------------------------------------+
| Device Screen                                               |
|                                                             |
|                                              +------------+ |
|                                              |   Handle   | |  <-- Persistent Window
|                                              |  (16x96dp) | |      (FLAG_NOT_FOCUSABLE)
|                                              +------------+ |
|                                                             |
+-------------------------------------------------------------+
                               |
                               | Tap or Left Swipe (>24dp)
                               v
+-------------------------------------------------------------+
| Transient Full-Screen Window (Attached on demand)           |
| [Transparent Scrim: Outside Taps Dismiss]                  |
|                                            +--------------+ |
|                                            | Slide-Out    | |  <-- Modal Drawer
|                                            | Card (Grid)  | |      (FLAG_NOT_TOUCH_MODAL)
|                                            +--------------+ |
+-------------------------------------------------------------+
```

1. **Persistent Handle Window**: Sized strictly to the handle bounds (16dp × 96dp) with `WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE`. It consumes zero screen real estate outside its bounds and never interferes with background apps.
2. **Transient Modal Drawer Window**: When the handle is tapped or swiped horizontally, a full-screen transparent modal window is mounted to `WindowManager`. It hosts the animated Compose drawer card and a transparent scrim. Tapping outside the drawer automatically dismisses and unbinds the window immediately.

### 2. Synthetic Compose Hosting (`OverlayLifecycleOwner`)
Jetpack Compose views (`ComposeView`) require lifecycle, saved-state, and view-model bindings that are normally provided by an `Activity` or `Fragment`. Because `SidebarService` is a background service with no Activity window, Smart SideBar implements **`OverlayLifecycleOwner`**:
- Implements `LifecycleOwner`, `SavedStateRegistryOwner`, and `ViewModelStoreOwner`.
- Attaches to `ComposeView` via `setViewTreeLifecycleOwner`, `setViewTreeSavedStateRegistryOwner`, and `setViewTreeViewModelStoreOwner`.
- Transitions lifecycle states explicitly (`RESUMED` when attached, `CREATED` when detached), allowing Compose state flows, animations, and effects to pause and conserve battery when the drawer is collapsed.

### 3. System Gesture Exclusion Management
Android 10+ enforces a strict **200dp vertical exclusion limit** per screen edge for system back gestures (`View.setSystemGestureExclusionRects`).
- Smart SideBar uses a compact 96dp handle, comfortably within the 200dp quota.
- When the drawer opens, handle gesture exclusion is released (`emptyList()`) to yield gesture bandwidth back to the OS.
- When the handle position is changed in settings, exclusion rects are recalculated and updated immediately.

### 4. Thread Concurrency & Safety
- **Service Scope**: Bound to `Dispatchers.Main.immediate` to orchestrate WindowManager attachments and View hierarchy operations safely.
- **Background Tasks**: App list inspection and foreground app detection query `UsageStatsManager` and `PackageManager` on background threads (`Dispatchers.Default` / `Dispatchers.IO`).
- **Defensive Main-Thread Guards**: Critical WindowManager methods (`setHandleVisibility`, `setHandleYFromPersistence`) check `Looper.myLooper() == Looper.getMainLooper()` and post to `mainHandler` if called off-thread, completely eliminating `CalledFromWrongThreadException`.

### 5. Crash-Loop Circuit Breaker & Lifecycle Resilience
To prevent runaway restarts caused by aggressive OEM battery killers or process kills:
- **`onTaskRemoved`**: When swiped away from the Recent Apps list, the service schedules a 1-second auto-recovery alarm using `AlarmManager`.
- **Circuit Breaker**: `AppRepository.recordServiceStartAndCheckCircuitBreaker()` monitors start timestamps. If more than 5 restarts happen within a 60-second window, the service trips the circuit breaker, posts a diagnostic notification to the user, and aborts further overlay attachments.

---

## What, Which & Why (Tech Stack & Design Decisions)

| Component | What / Which | Why It Was Used |
| :--- | :--- | :--- |
| **Language** | Kotlin 2.2.10 | Coroutines, flow operators, type safety, and modern Compose compiler integration. |
| **UI Toolkit** | Jetpack Compose (BOM 2026.02.01) + Material 3 | Declarative UI, smooth slide/fade animations (`AnimatedVisibility`), and clean theme token reuse across configuration and overlay windows. |
| **Dependency Injection** | Koin 4.1.0 (`koin-android`, `koin-androidx-compose`) | Lightweight, pure Kotlin DI without KSP/kapt code generation or reflection overhead. Perfect for fast builds and clean module isolation (`SidebarModule.kt`). |
| **Local Persistence** | AndroidX Preferences DataStore | Modern, asynchronous, transactional key-value storage replacing legacy `SharedPreferences`. |
| **Data Serialization** | Custom JSON Serializer (`SidebarConfigSerializer`) | Preserves **strict pinned package ordering** (`List<String>`) and schema versioning (`schemaVersion = 2`), which standard DataStore string sets (`Set<String>`) cannot do. |
| **Service Layer** | Android Foreground Service (`specialUse` type) | Complies with Android 14+ (API 34/35) FGS policy requirements. Configured with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` in the manifest. |
| **App Diagnostics** | Custom `DiagnosticRingBuffer` + `Service.dump()` | Captures the last 50 state transitions in memory. Zero external logging SDKs required; fully inspectable via `adb shell dumpsys`. |

---

## How to Customize

### 1. Handle Size, Shape & Visual Style
The handle constants and layout live in [`OverlayWindowManager.kt`](app/src/main/java/com/imtusharahmed/smartsidebar/overlay/OverlayWindowManager.kt) and [`SidebarHandle.kt`](app/src/main/java/com/imtusharahmed/smartsidebar/ui/overlay/SidebarHandle.kt):

- **Change Handle Dimensions**:
  ```kotlin
  // In OverlayWindowManager.kt (companion object)
  const val HANDLE_WIDTH_DP = 16   // Width of touch target
  const val HANDLE_HEIGHT_DP = 96  // Height of touch target (must be <= 200dp for gesture exclusion)
  ```
- **Change Handle Shape & Colors** in `SidebarHandle.kt`:
  ```kotlin
  // Handle shape (curved towards the left)
  val handleShape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)

  // Background and inner grip indicator
  Box(
      modifier = modifier
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
          // ...
  )
  ```

### 2. Drawer Card Layout & Grid Dimensions
The drawer card lives in [`SidebarDrawer.kt`](app/src/main/java/com/imtusharahmed/smartsidebar/ui/overlay/SidebarDrawer.kt):

- **Card Width & Height**:
  ```kotlin
  ElevatedCard(
      modifier = Modifier
          .width(200.dp)                     // Adjust drawer card width
          .heightIn(min = 160.dp, max = 480.dp), // Adjust vertical sizing bounds
      shape = RoundedCornerShape(20.dp),
      // ...
  )
  ```
- **Grid Columns & Icon Sizes**:
  ```kotlin
  LazyVerticalGrid(
      columns = GridCells.Fixed(2), // Change to 3 or 4 columns if desired
      // ...
  )

  // In ShortcutGridItem:
  Image(
      bitmap = iconBitmap,
      modifier = Modifier.size(44.dp) // Adjust icon size
  )
  ```

### 3. Position Range & Safe Insets
The position of the sidebar handle is locked outside the app and managed from the **Sidebar Position** card in `MainActivity.kt`:

- In [`ChangePositionDialog`](app/src/main/java/com/imtusharahmed/smartsidebar/MainActivity.kt), boundary math is calculated using screen insets:
  ```kotlin
  val handleHeightPx = (OverlayWindowManager.HANDLE_HEIGHT_DP * density).roundToInt()
  val marginPx = (OverlayWindowManager.DEFAULT_MARGIN_DP * density).roundToInt()
  ```
- You can customize presets (Top, Center, Bottom) or default Y offsets in [`AppShortcut.kt`](app/src/main/java/com/imtusharahmed/smartsidebar/model/AppShortcut.kt):
  ```kotlin
  const val DEFAULT_HANDLE_Y_OFFSET_PX = 300
  ```

### 4. Adding Custom Quick Actions or Tools
To add system tools (such as Flashlight, Wi-Fi toggle, or Calculator shortcut) into the drawer:
1. Define a tool identifier or data model in [`AppShortcut.kt`](app/src/main/java/com/imtusharahmed/smartsidebar/model/AppShortcut.kt).
2. Render tool buttons alongside pinned apps in [`SidebarDrawer.kt`](app/src/main/java/com/imtusharahmed/smartsidebar/ui/overlay/SidebarDrawer.kt).
3. Handle click actions in `onAppClick` or an `onToolClick` callback dispatched from `OverlayWindowManager.kt`.

---

## Diagnostics & Debugging

Smart SideBar includes a built-in diagnostic telemetry system. You can inspect the entire live operational state of the service and overlay directly from your terminal:

```bash
adb shell dumpsys activity service SidebarService
```

**Sample Output:**
```text
=== SideBar Diagnostic Dump ===
Service Uptime: 450s
Overlay Permission Granted: true
FGS Type: specialUse

--- Window & Lifecycle State ---
Overlay State: COLLAPSED
Handle Lifecycle: RESUMED
Drawer Lifecycle: CREATED
Handle Params: x=0 y=288 w=24 h=144 (96dp) screenH=960
Safe Screen Insets: top=36 bottom=0 minY=48 maxY=804
Active Gesture Exclusion Rect: Rect(0, 0 - 24, 144)
System Exclusion Quota: 96dp / 200dp

--- Recent Diagnostic Events (Last 50) ---
[15:34:06.188] [HANDLE_VISIBILITY] Handle shown
[15:34:27.480] [LAUNCH_INTENT] target=com.google.android.youtube source=drawer_pinned_grid result=SUCCESS latency=12ms
[15:35:08.856] [POSITION_UPDATE] Updated handle Y from persistence to 320
```

---

## Permissions & Requirements

- **Minimum SDK**: API 30 (Android 11)
- **Target SDK**: API 35 (Android 15)
- **Key Permissions**:
  - `android.permission.SYSTEM_ALERT_WINDOW`: Required to display the handle and drawer over other apps.
  - `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_SPECIAL_USE`: Required to maintain the persistent edge service on Android 14+.
  - `android.permission.QUERY_ALL_PACKAGES`: Required to list installed apps so you can choose which ones to pin.
  - `android.permission.PACKAGE_USAGE_STATS` *(Optional)*: Required only if you enable the "Hide When Apps Are Running" feature.
  - `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Recommended to prevent Android from aggressively terminating the overlay service.

---

## Building & Installing

### 1. Build via Gradle
```bash
# Debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest
```

### 2. Install to Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Setup on Device
1. Open **Smart SideBar** from your app drawer.
2. Grant **Display Over Other Apps** (Overlay Permission).
3. Tap **Enable SideBar Overlay**.
4. Tap **Change Position** to customize the vertical position of the handle with the live phone preview.
5. Tap the checkboxes next to installed apps to pin them to your sidebar.
