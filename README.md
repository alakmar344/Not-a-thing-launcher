# Not-a-Thing Launcher

[![Build and Release APK](https://github.com/alakmar344/Not-a-thing-launcher/actions/workflows/build.yml/badge.svg)](https://github.com/alakmar344/Not-a-thing-launcher/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/alakmar344/Not-a-thing-launcher?label=Latest%20Release&color=d71921)](https://github.com/alakmar344/Not-a-thing-launcher/releases/latest)

A Nothing OS–inspired Android home screen: monochrome, dot-matrix, quiet — with red used only where something needs your attention.

**Version 11.0** is built on **stock Android**: the home screen is AOSP **Launcher3** (the open-source launcher behind Pixel's), and Not a Thing is a design layer on top of it. Home screen, app drawer, folders, widgets, drag & drop, app shortcuts and settings all behave exactly like stock Android.

Everything you see is the Not a Thing v10 design; Launcher3 does the work behind it:

- **Not a Thing palette** – Launcher3's wallpaper-derived Material You colours are replaced by a fixed monochrome palette with Nothing red (all apps, folders, popups, widget picker, settings).
- **Space Grotesk** typography and red notification dots.
- **Home** – black (or bone) dot-grid backdrop, the LED clock and battery & date tiles on first run, and the "Search apps & web" pill under the dock.
- **Icons** – every app as a glyph in a disc (or colour artwork in a circle), on home, dock, folders and in the drawer.
- **Drawer** – dot-matrix "APPS" header with app count and settings, v10 search pill.
- **Customize sheet** on long-press (wallpaper & style, widgets, settings) and the v10 settings screen.
- **Dot-matrix elements** in the stock widget picker (long-press home → Widgets → *Not a Thing*): LED clock, battery & date, weather, media player.
- **Monochrome or colour icons**, backdrop, grid and more in the v10 settings (long-press home → Settings).

<p>
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_homeMono.png" width="24%" alt="Home" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_drawer.png" width="24%" alt="App drawer" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_customizeSheet.png" width="24%" alt="Customize" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_homeMono.png" width="24%" alt="Light mode" />
</p>

> Screenshot rendered by the Paparazzi tests in `app/src/test` (CI publishes fresh ones with every release).

### Project layout

| Path | What it is |
|---|---|
| `third_party/aosp/` | Unmodified AOSP Launcher3 + SystemUI libraries (`android-14.0.0_r75`), see its README |
| `launcher3/`, `iconloader/`, `animationlib/` | Gradle modules that build the AOSP sources; `launcher3/src` holds the platform stand-ins and the override layer |
| `app/` | The Not a Thing app (`com.nothinglauncher`): theme, glance, elements |
| `tools/` | Generators for Launcher3's flags and the Not a Thing palette |

### Building

GitHub Actions builds the debug and release APKs on every push (`.github/workflows/build.yml`) and attaches them to the release. Locally: `./gradlew assembleDebug` (JDK 17, Android SDK 35).

> **Upgrading from 10.x:** the APK installs over the old version (same package and signing key), but the home screen layout starts fresh, because stock Launcher3 keeps its own layout database. Older docs in `docs/` describe the 10.x launcher.

---

## 🔐 Release signing & update compatibility

Android only installs an update if it is signed with the **same key** as the installed app. Every old GitHub release (v2 → v10.0) was signed with a different throwaway key that GitHub Actions generated and then discarded. That is why updates failed.

**Fixed:** all builds are now signed with one permanent key, `keystore/stable-signing.p12`, and every build gets a higher `versionCode` (`2000 + GitHub run number`). Nothing needs configuring. From the next release on, just install the new APK over the old one.

**One last uninstall is unavoidable** for people who already have an old version. Moving an installed app to a new key ([APK Signature Scheme v3 key rotation](https://source.android.com/docs/security/features/apksigning/v3)) needs the *old* private key, and those keys no longer exist.

Optional: your own key can be supplied as the secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. Add them now or never, because changing keys later forces another uninstall.

### Optional: `.aab` files and re-signed old releases

The automation that pushes this repo is not allowed to edit `.github/workflows/`. If you want the `.aab` (Android App Bundle) attached to releases, and a one-click button that re-signs all old releases (v2 → v9.0) with the same key, paste the contents of `ci/workflows/build.yml` over `.github/workflows/build.yml` in the GitHub web editor. To re-sign the old releases, open **Actions → Build and Release APK → Run workflow** and tick *rebuild old releases*.

Build locally (Windows):

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

The release build enables R8 shrinking. Verify the signer before distributing:

```powershell
& "$env:ANDROID_HOME\build-tools\34.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-release.apk
```

---

## 🆕 What v10.0 GA Fixed & Added

| Area | Change |
|---|---|
| **Zero Idle Battery Consumption** | Hardware sensors (`CompassRepository`, `StepRepository`) now completely unregister listeners when the app is paused (`onStop`), the screen turns off, or another app enters the foreground. |
| **Drawer & Page Throttling** | The compass hardware bus is suspended in real-time when dragging down the app drawer (`drawerProgress > 0.05f`) or navigating to workspace pages without a compass element. |
| **Deep View Visibility Overrides** | All animated dot views (`SecondsTickerView`, `DotMatrixClockView`, `BatteryDotsView`, `MediaDotsView`, `StopwatchDotsView`, `VoiceRecorderDotsView`, `SystemMonitorDotsView`, `AnalogClockDotsView`, `WorldClockDotsView`) gate their loops behind `onVisibilityAggregated(isVisible)` and `isShown`, stopping all background handlers and value animators. |
| **Instant Cold-Start & Icon Cache** | In-memory `LruCache<String, Bitmap>(300)` caches monochrome and styled bitmaps across the entire session, eliminating synchronous canvas passes and main-thread stalls during app launches and scrolling. |
| **Surface Contrast & Dark Mode Borders** | Added missing `border_subtle` (`#232327`) and `surface_dark` (`#0C0C0E`) theme tokens in `res/values-night/colors.xml`, ensuring seamless drop zone bars and dialog dividers. |
| **Safe Schema v3 Migration** | `SCHEMA_VERSION = 3` upgrades without losing any layouts, folders, widgets, or preferences. |

---

## 🆕 What v9.0 Fixed & Added

| Area | Change |
|---|---|
| **Unified CellLayout Grid** | Custom 2D matrix ViewGroup replacing segmented stacks. Freely place and interleave apps, 1×1 circular folders, 2×2 enlarged folders, 2×2 max icons, bespoke elements, and hosted AppWidgets on a single continuous page. |
| **Nothing OS Folders & Max Tiles** | 1×1 circular dark disc folder icons with 2×2 mini previews; 2×2 enlarged interactive folder tiles with 4 direct launch slots; 2×2 square Max Icon tiles with monochrome icon and red dot accent. |
| **Onboarding** | First launch now really opens the welcome tour (it previously existed but was never wired), including the set-as-default prompt. Re-runnable from *Settings → Show welcome tour*. |
| **Double-Tap to Sleep** | Uses the accessibility global action (`GLOBAL_ACTION_LOCK_SCREEN`); the old code broadcast a no-op intent. If the optional service is off, the launcher explains instead of failing silently. |
| **Media Controller element** | Now a real player: live track/artist, real play/pause/next/previous via `MediaSessionManager` transport controls, disabled-but-honest state before notification access is granted, tap-to-open current player app. |
| **Folder rename** | New *Rename* chip inside the folder sheet (previously `renameFolder` existed with no UI). |
| **Element & widget reordering** | Long-press popups for bespoke elements and hosted widgets gained *Move Up / Move Down* alongside *Remove*, making pages fully editable. |
| **Settings → Permissions & capabilities** | Rows for Usage access, Notification access and Accessibility gestures with live on/off subtitles. |
| **Motion & microphone permissions** | Dedicated rows request `ACTIVITY_RECOGNITION` and `RECORD_AUDIO` in context, report sensor/permission state honestly, and refresh the live pedometer or recorder immediately. |
| **Weather & step personalisation** | Weather location can be a fixed city or automatic network lookup; the daily step goal is editable from Settings. Both persist across upgrades. |
| **Folder app actions** | Long-press an app inside a folder for Open, Move to home, Enlarge, Dock and App info. Dragging out of a folder is supported and never leaves a duplicate. |
| **Widget management** | Hosted widgets can be configured, made taller/shorter, reset to provider height, reordered within a page, moved between pages, or removed. |
| **Drag/drop integrity** | Home apps, dock apps, enlarged apps and folder contents share a single-copy invariant; dock copies are intentional. Drops route through native workspace, dock, folder-merge and top action zones. |
| **Data safety on upgrade** | `versionCode 11` replaces 8.x in place; `SCHEMA_VERSION` migrations keep `onboarding_complete` behavior for existing users. Release builds are signed with a stable project key so future versions install over this one. |

---

## ⚡ Highlights & Key Features

### Multi-Page Workspace & Dynamic Home
- **Horizontally Paginated Workspace** — Swipe left and right smoothly between customizable home screen pages.
- **Minimalist Dot Page Indicator** — Animated dot tracker that dynamically stretches and morphs with scroll position.
- **Page Management** — Add blank pages or remove pages on the fly directly from the *Home Customisation* sheet.
- **Max Icons (Enlarge 2×2)** — Expand any pinned app icon into a 2×2 hero tile matching Nothing OS 2.5/3.0.
- **Workspace App Folders** — Group apps into clean Nothing dot folders with mini-icon previews and folder manager dialog.

### Bespoke Nothing Elements Suite
- **LED-Panel Clock** — Dot-matrix display with pulsating accent colon, 12/24H format, and live 60-dot seconds ticker.
- **Compact Battery Ring** — 40-dot ring that turns red at ≤15% and breathes while charging.
- **Battery Gauge Hero Tile (v8.0)** — Full-width live battery % readout in DotFont with a 28-dot circular capacity meter and charging bolt.
- **Quick Note / Daily Quote (v8.0)** — Customizable dot-matrix scratchpad on your home screen; single tap to edit your daily quote or notes.
- **Wallpaper & Style Center (v8.0)** — Instant switching between Nothing Dot Matrix, Solid Minimalist (OLED black/white), and System Wallpaper.
- **Procedural Weather Tile** — Live dot-matrix temperature, procedural sun & cloud graphics, and condition forecasts.
- **Quick Toggles Tile** — 4 system controls (Wi-Fi, Bluetooth, Flashlight hardware torch, and Sound mode cycler).
- **Step Counter (Pedometer)** — 36-dot progress ring tracking daily steps with live count in dot-matrix typography.
- **Heading Compass** — Procedural dot-matrix compass rose with accent North indicator and degrees heading.
- **Screen Time & Wellbeing** — Daily active time in dot-matrix typography with a 7-segment dot usage bar.
- **Storage & RAM Meter** — Device capacity meter with DotFont free GB readout and circular dot fullness ring.
- **World Clock Tile** — Dual time-zone dot clock showing local and UTC times.
- **Third-Party AppWidgets** — Host any standard Android widget on any workspace page.

### Theming, Icons & Gestures
- **Pure OLED Dark & Minimalist Light Modes** — Pure `#000000` dark mode or bone-white `#F6F6F4` light mode.
- **Nothing Monochrome Icon Engine** — Uses Android 13+ monochrome themed icons when available, or procedural monochrome discs.
- **Double-Tap to Sleep** — Double-tap any empty home area to turn off the display.
- **Follows-Your-Finger Drawer** — Fluid drag physics with real-time background blur.
- **Multi-Tier Ranked Search** — Prefix, word boundary, substring, and acronym matches ("yt" → YouTube) with web search fallback.
- **A–Z Alphabet Dot Scroller** — Fast alphabetical scrub bar with dot-matrix letter bubble and haptic ticks.

---

## 🏛️ Architecture & Source Structure

| Path | Description |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Hosts home + drawer, finger-tracking drawer controller, popups, and wallpaper/style sheet |
| `app/src/main/java/.../HomeFragment.kt` | Multi-page horizontal workspace (`workspaceRecycler` + `PagerSnapHelper`), page indicator, dock |
| `app/src/main/java/.../WorkspacePageAdapter.kt` | Dynamically renders bespoke elements, 3rd-party widgets, and drag-and-drop app grids |
| `app/src/main/java/.../BatteryGaugeDotsView.kt` | Full-width live battery % readout with circular 28-dot capacity meter |
| `app/src/main/java/.../QuoteDotsView.kt` | User scratchpad / daily quote element rendered in DotFont |
| `app/src/main/java/.../Sheets.kt` | Bottom sheets: Home Customisation, Wallpaper & Style, Edit Note, Confirmations |
| `app/src/main/java/.../DotFont.kt` | Procedural 5×7 dot-matrix rasterizer for alphanumeric characters and system glyphs |
| `app/src/main/java/.../DotGridDrawable.kt` | Signature Nothing dot grid background canvas |
| `app/src/test/java/.../ScreensTest.kt` | Paparazzi layoutlib visual regression and screenshot testing suite |

---

## 🧪 Testing with Paparazzi

Render pixel-perfect screenshot captures without an emulator:

```bash
# Record all snapshots into app/src/test/snapshots/images
./gradlew recordPaparazziDebug

# Verify layouts against recorded snapshots
./gradlew testDebugUnitTest
```
