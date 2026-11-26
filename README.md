# Not-a-Thing Launcher

[![Build and Release APK](https://github.com/alakmar344/Not-a-thing-launcher/actions/workflows/build.yml/badge.svg)](https://github.com/alakmar344/Not-a-thing-launcher/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/alakmar344/Not-a-thing-launcher?label=Latest%20Release&color=d71921)](https://github.com/alakmar344/Not-a-thing-launcher/releases/latest)

A Nothing OS–inspired Android home screen built for both phones and big screens (tablets, foldables, and docked displays): monochrome, dot-matrix, tactile — with hardware phosphor accents used where something needs your attention.

Under the hood it is **stock AOSP Launcher3** (`android-14.0.0_r75`) with the Not a Thing experience layered on top, so workspaces, folders, drag & drop, widget resizing, and app shortcuts follow stock Android cleanly.

**Version 14** · runs on Android 8.0 – 17 · targets Android 16 (API 36)

<p>
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_homeMono.png" width="24%" alt="Home" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_drawer.png" width="24%" alt="App drawer" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_customizeSheet.png" width="24%" alt="Customize" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_homeMono.png" width="24%" alt="Light mode" />
</p>

## What you get in v14

- **Big-Screen Telemetry Studio (`w600dp`+):** Dual-column 2×2 home bento grid (6+ columns), 2-column Bento Essential Space with Habit Pegboard & Now Playing media deck, 2-column Bento Settings with a live **Studio 14** dot-matrix clock preview, 2×2 preset studio hub, multi-column drawer lists (`1/2/3` columns), and 2-column Command Search cards.
- **4-Stage Glyph Desk HUD (`DeskHudActivity`):** Full-screen ambient dashboard with persistent **COCKPIT**, **CLOCK** theatre, **FOCUS** zen Pomodoro dial, and procedural audio- & timer-reactive **GLYPH** LED matrix (`GlyphMatrixCanvasView`), 1-tap Habit check-in, Keep-Awake, OLED pixel shift, and hardware keyboard shortcuts (`1–4`, `Tab`/`M`, `C`, `F`, `H`, `Space`).
- **12 dot-matrix widgets & Per-Widget Surfaces:** All v13 widgets plus **Desk Briefing (`DeskBriefingWidget`)** with live Focus Timer toggle, hover-free per-widget finish customization (**Default**, **Obsidian Dark**, **Bone Paper**, **Frosted Glass**), **Essential Stack** Max Icon mode (1 giant icon or 2×2 stack), and 12h rain-probability + wind speed telemetry in `WeatherWidget`.
- **Smart Drawer Categories & Keyboard Search:** Instant **GRID ↔ LIST ↔ CATS** (`AppCategorizer`) drawer switching, press `/` or Search on Home to launch Command Search (`timer`, `note`, `preset`, hex color inspect, UUID/dice/coin utilities, calculator, converters, fuzzy/acronym apps), recent apps strip, and biometric Hidden Vault.

Full guide: [`docs/FEATURES.md`](docs/FEATURES.md).

## Install and update

Download **`app-release.apk`** from the newest [release](https://github.com/alakmar344/Not-a-thing-launcher/releases)
and set it as your home app (or launch **Not a Thing** from the drawer to open the guided Setup & Permissions Hub).
Every push to `main` or matching `v*` tag builds signed APKs that install over the previous build.
See [`docs/RELEASING.md`](docs/RELEASING.md) for signing and upgrade details.

> Coming from a release before 11.0? Uninstall it once first: those builds used throwaway keys.

## Build

Requires JDK 17 and Android SDK (`compileSdk 35`, `minSdk 26`, `targetSdk 36`).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # R8-minified APK + verifyReleaseReflection check
./gradlew testDebugUnitTest    # unit, Robolectric, and Paparazzi golden tests
./gradlew recordPaparazziDebug # re-record screenshot goldens after a visual change
```

On Windows use `gradlew.bat`. Paparazzi screenshots run on JVM layoutlib without an emulator
(note: Windows Application Control environments that block `libicuuc_stubdata.dll` can still
run non-Paparazzi unit/Robolectric tests such as `VisualTierTest`, `LauncherRuntimeTest`,
`V13FeaturesTest`, and `V14FeaturesTest`).

## Docs

| | |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Source layout, AOSP extension points, v14 subsystems, and R8 reflection contract |
| [`docs/FEATURES.md`](docs/FEATURES.md) | Complete guide to Big-Screen Studio, Desk HUD, 12 widgets, Essential Space, Search, and Settings |
| [`docs/SCREENSHOTS.md`](docs/SCREENSHOTS.md) | Visual golden tests (phone & tablet) and host notes |
| [`docs/RELEASING.md`](docs/RELEASING.md) | How builds are published and installed as updates; signing |
| [`CHANGELOG.md`](CHANGELOG.md) | Release history through v14 |
| [`AGENTS.md`](AGENTS.md) | Rules for AI coding agents working in this repo |
| [`docs/archive/`](docs/archive/) | Archived documentation for v13, v12, and pre-v11.0 releases |

## Project layout

| Path | What it is |
|---|---|
| `app/` | The Not a Thing app (`com.nothinglauncher`): theme, `-1` overlay, Desk HUD, 12 widgets, search, drawer, vault, settings |
| `launcher3/`, `iconloader/`, `animationlib/` | Gradle modules compiling upstream AOSP sources (`launcher3/src` holds stand-ins and `src_ui_overrides`) |
| `third_party/aosp/` | Unmodified AOSP Launcher3 + SystemUI libraries (`android-14.0.0_r75`) |
| `tools/` | Generators for Launcher3's flags and theme; R8 release-reflection verification |
| `keystore/` | Shared signing key (`stable-signing.p12`) for in-place updates |

Fonts: Space Grotesk and Space Mono, SIL Open Font License (`licenses/`).
