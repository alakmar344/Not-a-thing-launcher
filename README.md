# Not-a-thing-launcher

A lightweight Android launcher inspired by the Nothing Launcher look and feel.

> Shipped across **3 tagged releases (v2, v3, v4)** with debug + release APKs attached to each — see [Releases](https://github.com/alakmar344/Not-a-thing-launcher/releases). Builds are produced by CI (`.github/workflows/build.yml`).

## Download APK

You can download the latest APK builds from the GitHub Releases page:

- **[Download latest APK](https://github.com/alakmar344/Not-a-thing-launcher/releases/latest)**

Each release includes:
- `app-debug.apk`
- `app-release.apk`

## About the Project

Not-a-thing-launcher is a custom Android launcher project that focuses on a clean home-screen experience with a Nothing-style UI direction.  
It is intended for learning, experimentation, and community contributions.

## Features

Everything below maps to a real source file in this repository — nothing is aspirational:

- **Dot-matrix clock** — `DotMatrixClockView.kt` renders the signature Nothing-style time display
- **Gestures** — `GestureHandler.kt` powers swipe interactions on the home screen
- **App drawer & home screen** — `AppDrawerFragment.kt` + `HomeFragment.kt` with `AppAdapter` / `HomeIconAdapter`
- **Folders & dock** — `FolderManager.kt` / `FolderInfo.kt`, `DockAdapter.kt`
- **Widgets** — `WidgetHostManager.kt` hosts third-party widgets on the home screen
- **Wallpapers** — `WallpaperPickerManager.kt`
- **Boot persistence** — `BootReceiver.kt` keeps the launcher selected after restarts
- **MVVM structure** — `LauncherViewModel.kt` keeps state logic out of the views

### Release timeline

Verified against the GitHub Releases API:

| Version | Published | APK assets |
|---|---|---|
| v2 | 2026-04-08 | `app-debug.apk`, `app-release.apk` |
| v3 | 2026-04-08 | `app-debug.apk`, `app-release.apk` |
| v4 | 2026-05-25 | `app-debug.apk`, `app-release.apk` |

Every version is downloadable from
[Releases](https://github.com/alakmar344/Not-a-thing-launcher/releases) —
debug and release builds for each.

## Build from Source

From the repository root:

```bash
./gradlew assembleDebug assembleRelease
```

Generated APKs are available in:
- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`

## Contributing

Contributions are welcome. Please open an issue for bug reports or feature requests, then submit a pull request with clear change details.
