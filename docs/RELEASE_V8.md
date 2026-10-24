# Not-a-Thing Launcher: Release v8.0 Notes

**Release Name:** Release v8 (`Version 8.0`, `versionCode 8`)  
**Target SDK:** Android 14 (API 34)  
**Min SDK:** Android 8.0 (API 26)  
**Build System:** GitHub Actions CI (`.github/workflows/build.yml`)

---

## 🌟 Overview

Release **v8** is a major milestone for **Not-a-Thing Launcher**, bringing significant enhancements to the multi-page workspace, introduction of new bespoke Nothing elements, complete automated Paparazzi screenshot testing in GitHub Actions, and crucial bug fixes across widget lifecycle and layoutlib testing suites.

---

## 🚀 What's New in v8.0

### 1. New Bespoke Elements
- **Battery Gauge Hero Tile (`BatteryGaugeDotsView`)**:
  - Full-width hero widget displaying live device battery percentage in `DotFont` typography.
  - Real-time 28-dot circular capacity meter with accent charging bolt indicator.
  - Status indicators: `CHARGING`, `LOW BATTERY` (≤15% in Nothing red accent), or `STATUS · NORMAL`.
  - Tap opens system battery settings; long-press opens bespoke element management.
- **Quick Note / Daily Quote (`QuoteDotsView`)**:
  - Customizable dot-matrix scratchpad directly on your home screen canvas.
  - Live accent dot status indicator.
  - Single tap opens the quick note editor dialog to set daily quotes, reminders, or goals.

### 2. Wallpaper & Style Center (`Sheets.showWallpaperStyle`)
- Dedicated modal sheet accessible directly via home screen long-press.
- **Background Mode**: Instant 1-tap switching between **Nothing Dot Matrix** (`bg_style_dots`), **Solid Minimalist** (`bg_style_solid`), and **System Wallpaper** (`bg_style_wallpaper`).
- **Theme Mode**: Switch between **System Default**, **Dark (OLED Black)**, and **Light (Bone White)**.
- Deep link to Android system wallpaper picker.

### 3. Multi-Page Workspace Management
- Add blank pages or remove empty/unused pages on the fly from the Home Customisation sheet.
- Active page dot elongates smoothly in the minimalist page indicator as you swipe across pages.

---

## 🐛 Bug Fixes & Refinements

1. **ScreensTest Kotlin Compiler Overload Conflict**:
   - **Issue:** Commit `d6a510c` introduced two methods with the identical signature `@Test fun customizeSheet()` in `ScreensTest.kt`, failing Kotlin compilation with `Conflicting overloads`.
   - **Fix:** Refactored the second test to `@Test fun customizeSheetStandalone()`, isolating the modal sheet presentation from the home overlay test.

2. **Home Row Battery Tile Initial State Recovery (`BatteryDotsView`)**:
   - **Issue:** The compact battery ring on the top home row initialized with `level = 0` and was never updated with live device battery status, displaying 0% upon launch.
   - **Fix:** Added sticky intent query (`Intent.ACTION_BATTERY_CHANGED`) in `init` and automatic `BroadcastReceiver` registration across window attachment lifecycles (`onAttachedToWindow` / `onDetachedFromWindow`).

3. **Element Layout Padding Alignment**:
   - **Issue:** `widget_battery_dots.xml` and `widget_quote.xml` applied padding inside custom views while lacking padding on the outer container, causing click ripples and touch targets to misalign with system tiles.
   - **Fix:** Standardized root `LinearLayout` padding to `@dimen/tile_padding` and adjusted custom view heights to 76dp matching all system tiles (`StorageDotsView`, `ScreenTimeDotsView`).

4. **CI & Paparazzi Screenshot Automation**:
   - Integrated `./gradlew recordPaparazziDebug` directly into GitHub Actions workflow.
   - Automated uploading of all snapshot captures (`app/src/test/snapshots/images/*.png`) as workflow artifacts and release assets.

---

## 📦 APK Downloads & Verification

The APKs for Release v8 are compiled automatically by GitHub Actions from source:

| File | Type | Description |
|---|---|---|
| **`NothingLauncher-release.apk`** | Production Release | ProGuard-optimized, minified release APK |
| **`NothingLauncher-debug.apk`** | Debug Build | Full debug logging and instrumentation |
| **`NothingLauncher-screenshots.zip`** | Paparazzi Renders | Complete set of pixel-perfect layout captures |

Direct download links:
- **[Latest GitHub Release (v8)](https://github.com/alakmar344/Not-a-thing-launcher/releases/latest)**
