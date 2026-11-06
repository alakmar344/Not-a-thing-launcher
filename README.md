# Not-a-Thing Launcher

[![Build and Release APK](https://github.com/alakmar344/Not-a-thing-launcher/actions/workflows/build.yml/badge.svg)](https://github.com/alakmar344/Not-a-thing-launcher/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/alakmar344/Not-a-thing-launcher?label=Latest%20Release&color=d71921)](https://github.com/alakmar344/Not-a-thing-launcher/releases/latest)

A Nothing OS–inspired Android home screen: monochrome, dot-matrix, quiet — with red used only
where something needs your attention.

Under the hood it is **stock AOSP Launcher3** (the open-source launcher behind Pixel's) with the
Not a Thing design layered on top, so the home screen, drawer, folders, widgets, drag & drop and
app shortcuts all behave exactly like stock Android.

**Version 11.1** · runs on Android 8.0 – 17 · targets Android 16 (API 36)

<p>
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_homeMono.png" width="24%" alt="Home" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_drawer.png" width="24%" alt="App drawer" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_customizeSheet.png" width="24%" alt="Customize" />
  <img src="app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_homeMono.png" width="24%" alt="Light mode" />
</p>

## What you get

- **Not a Thing palette.** A fixed monochrome + Nothing red palette replaces the wallpaper-derived
  Material You colours everywhere (drawer, folders, popups, widget picker, settings).
- **Home.** Dot-grid, black or wallpaper backdrop, the LED clock and battery & date tiles on first
  run, and a "Search apps & web" pill under the dock.
- **Icons.** Each app as a glyph in a disc, or its colour artwork in a circle, on home, dock,
  folders and drawer.
- **Dot-matrix widgets** in the stock picker (long-press home → Widgets → *Not a Thing*): LED
  clock, battery & date, weather, media player.
- **Customize sheet** on long-press and the Not a Thing settings screen (grid, icon style,
  backdrop, seconds ticker, weather location, …).

Full list: [`docs/FEATURES.md`](docs/FEATURES.md).

## Install and update

Download **`app-release.apk`** from the newest [release](https://github.com/alakmar344/Not-a-thing-launcher/releases)
and set it as your home app. Every push to `main` publishes a new build that installs over the
previous one. [`docs/RELEASING.md`](docs/RELEASING.md) explains how, including signing.

> Coming from a release before 11.0? Uninstall it once first: those builds were signed with
> keys that no longer exist.

## Build

Requires JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # R8-minified, then checked by verifyReleaseReflection
./gradlew testDebugUnitTest    # unit + Robolectric tests, screenshot diff against the goldens
./gradlew recordPaparazziDebug # re-record screenshot goldens after a visual change
```

On Windows use `gradlew.bat`. Screenshots are rendered by [Paparazzi](https://github.com/cashapp/paparazzi)
on the JVM, so no emulator is needed.

## Docs

| | |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the Not a Thing layer hooks into Launcher3 |
| [`docs/FEATURES.md`](docs/FEATURES.md) | Full feature list |
| [`docs/SCREENSHOTS.md`](docs/SCREENSHOTS.md) | All current screenshots |
| [`docs/RELEASING.md`](docs/RELEASING.md) | How builds are published and installed as updates; signing |
| [`CHANGELOG.md`](CHANGELOG.md) | What changed in each version |
| [`AGENTS.md`](AGENTS.md) | Rules for AI coding agents working in this repo |
| [`docs/archive/`](docs/archive/) | The pre-11.0 from-scratch launcher, kept for history only |

## Project layout

| Path | What it is |
|---|---|
| `app/` | The Not a Thing app (`com.nothinglauncher`): theme, widgets, settings, icon style |
| `launcher3/`, `iconloader/`, `animationlib/` | Gradle modules that compile the AOSP sources; `launcher3/src` holds platform stand-ins and the override layer |
| `third_party/aosp/` | Unmodified AOSP Launcher3 + SystemUI libraries (`android-14.0.0_r75`) |
| `tools/` | Generators for Launcher3's flags and the palette; the release-build reflection check |
| `keystore/` | The public signing key every build uses, so updates install in place |

Fonts: Space Grotesk and Space Mono, SIL Open Font License (`licenses/`).
