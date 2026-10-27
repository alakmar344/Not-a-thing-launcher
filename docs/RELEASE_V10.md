# Release v10.0 — General Availability: Zero-Idle Battery, Instant Cold-Start & Surface Polish

**Package:** `com.nothinglauncher`  
**Version:** 10.0 (`versionCode 12`)  
**Min SDK:** 26 · **Target SDK:** 34  
**Schema Version:** 3  
**Artifacts:**
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
- `dist/Not-a-Thing-Launcher-10.0-release.apk`

---

## The Road to 10.0: First Public Release

v10.0 marks the transition from beta to the first official General Availability (GA) public launch of Not-a-Thing Launcher. The directive for v10.0 was clear: **"we are not adding, we are making it better."**

Rather than bloating the launcher with gimmick features, v10.0 is an exhaustive deep-optimization and precision-polishing release focused on what makes a launcher worthy of being the permanent daily driver on anyone's device: **zero idle battery drain**, **instantaneous cold startup and scrolling**, **pixel-perfect surface contrast**, and **seamless non-destructive upgrades**.

---

## Key Improvements in v10.0

### 1. Zero-Idle Battery Architecture (Lifecycle & Visibility Gating)
- **Hardware Sensor Disconnection:** `CompassRepository` and `StepRepository` now implement explicit lifecycle pausing. When the launcher is backgrounded or the screen turns off (`onStop`), sensor listeners (`TYPE_ROTATION_VECTOR`, `TYPE_ACCELEROMETER`, `TYPE_MAGNETIC_FIELD`, `TYPE_STEP_COUNTER`) are immediately and completely unregistered from Android's `SensorManager`, stopping kernel sensor interrupts and bus polling.
- **Drawer Scrubbing Sensor Throttling:** When the user swipes open the app drawer (`drawerProgress > 0.05f`), compass sensor updates are suspended in real-time, eliminating redundant compass computations while browsing apps.
- **Page-Aware Sensor Gating:** `HomeFragment` monitors the visible workspace page (`setCurrentPage`). If the current page does not contain a compass element, compass hardware sampling is paused automatically.
- **Deep View Visibility Gating:** All 9 animated custom dot views (`SecondsTickerView`, `DotMatrixClockView`, `BatteryDotsView`, `MediaDotsView`, `StopwatchDotsView`, `VoiceRecorderDotsView`, `SystemMonitorDotsView`, `AnalogClockDotsView`, `WorldClockDotsView`) now implement `onVisibilityAggregated(isVisible)` and `onVisibilityChanged()` guards. When views are offscreen, obscured, detached, or invisible, all `postDelayed` ticker runnables and `ValueAnimator` pulse loops are cancelled immediately.

### 2. Instant App Cold-Start & In-Memory Icon Caching
- **O(1) Icon Retrieval:** Introduced an in-memory `LruCache<String, Bitmap>(300)` in `IconFactory` keyed by component name and user serial (`ComponentName#UserSerial#IconStyle#ThemedMode`).
- **Elimination of UI Thread Stalls:** Eliminated synchronous per-app canvas allocations, monochrome color filter matrices, and bitmap rasterization passes on launch, page switches, and drawer scrolls.
- **Intelligent Invalidation:** Icon cache automatically purges on package addition, removal, or update via `LauncherApps.Callback`, as well as on icon style or theme mode toggles.

### 3. Surface UI/UX Contrast & Night Mode Polish
- **True OLED Dark Mode & Bone-White Light Mode:** Fully verified contrast across pure black `#000000` dark surfaces and `#F6F6F4` light backgrounds.
- **Dark Mode Border Contrast Fix:** Fixed missing `border_subtle` (`#232327`) and `surface_dark` (`#0C0C0E`) resource tokens in `res/values-night/colors.xml`, resolving high-contrast white divider lines on dark drop zone bars during drag-and-drop operations.
- **Edge-to-Edge System Insets:** Complete window insets handling via `WindowCompat.setDecorFitsSystemWindows(window, false)` with top drop-zone padding dynamically adapting to system status bars and cutouts.

### 4. Non-Destructive Data Schema Migration (`SCHEMA_VERSION = 3`)
- Upgraded layout schema to version 3.
- Implements backward-compatible preference and workspace migration that preserves 100% of user layouts, custom pages, dock assignments, pinned apps, folders, and preferences without resetting any data.

---

## Build & Release Verification

```powershell
# Compile Kotlin debug
.\gradlew.bat compileDebugKotlin --console=plain

# Run Robolectric & unit tests
.\gradlew.bat testDebugUnitTest --tests "com.nothinglauncher.*" --console=plain

# Assemble release APK with R8 optimization & signing
.\gradlew.bat assembleRelease --console=plain
```
