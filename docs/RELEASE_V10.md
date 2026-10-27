# Release v10.0 — General Availability: Zero-Idle Battery, Instant Cold-Start & Surface Polish

**Package:** `com.nothinglauncher`  
**Version:** 10.0 (`versionCode 12`)  
**Min SDK:** 26 · **Target SDK:** 34  
**Schema Version:** 3  
**Artifacts:**
- `app/build/outputs/apk/debug/app-debug.apk` (6.50 MB)
- `app/build/outputs/apk/release/app-release.apk` (5.05 MB)
- `dist/Not-a-Thing-Launcher-10.0-release.apk` (5.05 MB)

---

## The Road to 10.0: First Public Release

v10.0 marks the transition from beta to the first official General Availability (GA) public launch of Not-a-Thing Launcher. The directive for v10.0 was clear: **"we are not adding, we are making it better."**

Rather than bloating the launcher with gimmick features, v10.0 is an exhaustive deep-optimization and precision-polishing release focused on what makes a launcher worthy of being the permanent daily driver on anyone's device: **zero idle battery drain**, **instantaneous cold startup and scrolling**, **pixel-perfect surface contrast**, and **seamless non-destructive upgrades**.

---

## In-Depth Architectural Improvements

### 1. Zero-Idle Battery Architecture (Lifecycle & Visibility Gating)

#### A. Hardware Sensor Disconnection
- **`CompassRepository` & `StepRepository` Lifecycle Control**:
  - Previously, sensor listeners registered on first read and remained connected to the kernel `SensorManager` hardware bus continuously, burning battery even when the launcher was in the background or the screen was turned off.
  - In v10.0, `CompassRepository` and `StepRepository` provide explicit `pause()` and `resume()` APIs.
  - `MainActivity.onStart()` calls `resume()`; `MainActivity.onStop()` calls `pause()`.
  - When paused, sensor listeners (`TYPE_ROTATION_VECTOR`, `TYPE_ACCELEROMETER`, `TYPE_MAGNETIC_FIELD`, `TYPE_STEP_COUNTER`) are completely unregistered, halting hardware interrupts and polling loops.

#### B. Dynamic Drawer & Workspace Page Throttling
- **Drawer Scrubbing Suspension**:
  - In `MainActivity.applyDrawerProgress()`, if `drawerProgress > 0.05f`, `CompassRepository.pause()` is invoked automatically. As soon as the user finishes app browsing and returns to home (`drawerProgress <= 0.05f`), compass updates resume smoothly.
- **Page-Aware Sensor Throttling**:
  - In `HomeFragment.setCurrentPage(index)`, the visible page's items are inspected. If the page does not contain `BespokeElement.COMPASS.id`, `CompassRepository.pause()` is called, eliminating unnecessary compass polling when viewing non-compass pages.

#### C. Custom Dot View Visibility Gating
All 9 animated custom dot views now implement Android's `onVisibilityAggregated(isVisible)` and `isShown` guards:
1. **`SecondsTickerView.kt`**: Gated the 1000ms `postDelayed` loop behind `isShown`. Implemented `onVisibilityAggregated(isVisible)` to post or remove callbacks.
2. **`DotMatrixClockView.kt`**: Colon blink and seconds ticker loops check `visibility == VISIBLE && isShown`.
3. **`BatteryDotsView.kt`**: `updatePulse()` checks `visibility == VISIBLE && isShown`. ValueAnimator breathing loops are cancelled when hidden.
4. **`MediaDotsView.kt`**: Media progress and equalizer bars cancel animators when `!isVisible`.
5. **`StopwatchDotsView.kt`**: Stopwatch animation handler cancels when detached or hidden.
6. **`VoiceRecorderDotsView.kt`**: Waveform visualization stops drawing when offscreen.
7. **`SystemMonitorDotsView.kt`**: Periodic RAM/storage stat refreshes stop when unattached or hidden.
8. **`AnalogClockDotsView.kt`**: Clock second ticks halt when offscreen.
9. **`WorldClockDotsView.kt`**: Dual-zone time tickers pause when obscured.

---

### 2. Instant App Cold-Start & In-Memory Icon Caching

#### The Problem
Stock launchers execute canvas creation, drawable layer drawing, adaptive monochrome color filtering, and bitmap allocation synchronously for every installed app before showing the home screen or app drawer. This caused frame drops and noticeable lag on cold boot.

#### The v10.0 Solution
- **`IconFactory.kt` LRU Cache**:
  - Implemented an in-memory `LruCache<String, Bitmap>(300)`.
  - Cache key schema: `ComponentName#UserSerial#IconStyle#ThemeMode`.
  - First render creates the bitmap; all subsequent requests resolve in $O(1)$ memory lookup without touching the canvas or allocating memory.
- **Cache Invalidation Lifecycle**:
  - `LauncherViewModel` hooks into `LauncherApps.Callback` to invalidate the cache when packages are installed, updated, or removed.
  - Changing icon style (*Mono* vs. *Color*) in Settings immediately flushes the cache.
  - Toggling theme mode (Light vs. Dark) in `MainActivity.applyThemeMode()` purges the cache so icons adapt their tinting instantly.

---

### 3. Surface UI/UX Contrast & Night Mode Polish

- **True OLED Dark Mode**: Pure `#000000` canvas ensuring 0% pixel power consumption on OLED panels.
- **Minimalist Light Mode**: Clean bone-white `#F6F6F4` canvas with deep ink `#0C0C0E` text.
- **Drop-Zone Bar Contrast Fix**:
  - Added missing `border_subtle` (`#232327`) and `surface_dark` (`#0C0C0E`) tokens to `res/values-night/colors.xml`.
  - Previously, drop zone divider lines rendered in high-contrast light grey (`#DCDCD8`) in dark mode; they now blend seamlessly into dark surfaces.
- **Edge-to-Edge System Insets**:
  - Enabled `WindowCompat.setDecorFitsSystemWindows(window, false)`.
  - Applied dynamic window insets listeners to top drop-zone action bars and drawer containers to cleanly respect camera notches, status bars, and navigation gesture bars.

---

### 4. Non-Destructive Data Schema Migration (`SCHEMA_VERSION = 3`)

- Upgraded layout schema to version 3.
- Implements backward-compatible preference and workspace migration that preserves 100% of user layouts, custom pages, dock assignments, pinned apps, folders, and preferences without resetting any data.

---

## Verification & Test Results

| Suite / Build Step | Details | Status |
|---|---|---|
| **`compileDebugKotlin`** | Full incremental Kotlin bytecode compilation | **PASSED** |
| **`GridMathTest`** | Discrete 2D grid allocation & coordinate placement math | **PASSED** (100%) |
| **`AppDragTest`** | Spring-loaded dragging & folder merge logic (Robolectric) | **PASSED** (100%) |
| **`DragDropTest`** | End-to-end workspace & dock drop resolution (Robolectric) | **PASSED** (100%) |
| **`assembleRelease`** | R8 shrinking, ProGuard optimization, Release signing | **PASSED** (`5.05 MB`) |
| **`assembleDebug`** | Full debug APK build with verbose logging | **PASSED** (`6.50 MB`) |

---

## Upgrade Guide for Users

- **Direct Over-the-Air / APK Upgrade**:
  - Install `Not-a-Thing-Launcher-10.0-release.apk` directly over any existing 8.x or 9.x installation.
  - All existing workspace pages, app folders, Max Icons, bespoke widgets, dock shortcuts, and custom settings will remain intact without needing to reconfigure.
