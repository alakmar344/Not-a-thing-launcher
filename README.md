# Not-a-thing-launcher

A Nothing OS–inspired Android home screen: monochrome, dot-matrix, quiet — with red used
only where something needs your attention.

![Home, drawer, long-press menu and customize sheet](docs/showcase_1.png)

![Settings, search, colour icons and the empty / low-battery state](docs/showcase_2.png)

> All images above are real renders of the app's layouts and views, produced by the
> screenshot tests in `app/src/test` (Paparazzi / layoutlib) — not mock-ups.

## Download APK

- **[Download latest APK](https://github.com/alakmar344/Not-a-thing-launcher/releases/latest)**

Each release includes `app-debug.apk` and `app-release.apk`, built by CI
(`.github/workflows/build.yml`) on every push to `main`. After installing, press Home and
pick **Not a Thing Launcher**, or open the launcher's *Settings → Default home app*.

## Features

### Home
- **LED-panel clock** — the time is lit on a full grid of unlit dots, like a real dot-matrix
  display. The colon blinks once per second in the accent colour, and a 60-dot **seconds
  ticker** runs underneath. Follows the system 12/24-hour setting. Tap to open alarms.
- **Battery tile** — a ring of 40 dots with the percentage in dot-matrix type. It turns red
  at ≤15%, and the next dot breathes while charging. Tap for battery usage.
- **Calendar tile** — month, dot-matrix day number, weekday and your next alarm. Tap to
  open your calendar.
- **Pinned apps** — long-press and drag to reorder. Empty home shows a dotted
  "pin apps here" hint.
- **Dock** (up to 5 apps) and a **search pill** that opens the drawer with the keyboard ready.
- **Third-party widgets** — add them from *Customize → Widgets* (grouped by app, with
  previews). Long-press a widget to remove it.
- **Backgrounds:** a signature dot grid, pure black, or your own wallpaper (with soft
  scrims so text stays readable).

### App drawer
- **Follows your finger** — swipe up anywhere on home and the drawer tracks the gesture,
  then settles with a fling-aware animation. Home blurs behind it on Android 12+.
  Swipe down (from the top of the list) to close.
- **Ranked search** — prefix, then word-start, then substring, then initials ("gm" → Google
  Maps). Press enter to open the top hit. No match offers a one-tap web search.
- **Recent apps** row, **A–Z dot scroller** with a dot-matrix letter bubble, and
  **hidden apps** (restore them in Settings).

### Long-press menu
App **shortcuts** (when set as default home), plus *Pin to home*, *Add to dock*,
*App info*, *Hide* and *Uninstall* (system apps can't be uninstalled, so it's not offered).

### Icons
- **Mono** (default) — uses Android 13+ themed (monochrome) icons when apps provide them.
  Otherwise it falls back to a crafted greyscale treatment on a dark disc.
- **Color** — the original artwork, uniformly clipped to a circle.

### Settings
App labels, 4 or 5 columns, seconds ticker, icon style, background, accent (red/white),
recent apps, auto-keyboard, hidden apps, swipe-down action (notifications or search),
default home app, wallpaper, and *Reset layout*.

## Architecture

| File | Role |
|---|---|
| `MainActivity.kt` | Hosts home + drawer, the finger-tracking drawer controller, popups, the widget bind/configure flow, backgrounds, and Home/back handling |
| `HomeFragment.kt` | Tiles (battery / time / alarm broadcasts), pinned grid with drag-reorder, dock, widgets, swipe gestures |
| `AppDrawerFragment.kt` | Drawer list, search ranking, recents, A–Z scroller, keyboard |
| `LauncherViewModel.kt` | Loads apps via `LauncherApps` with live package callbacks; home/dock/recents/hidden state, defaults and migration from v1 |
| `LauncherPrefs.kt` | Typed settings + layout persistence |
| `DotFont.kt`, `DotMatrixView.kt`, `DotMatrixClockView.kt` | Hand-built 5×7 dot-matrix typeface and LED-panel renderer |
| `BatteryDotsView.kt`, `SecondsTickerView.kt`, `DotGridDrawable.kt` | Custom-drawn widgets and background |
| `SwipeGestureLayout.kt`, `AlphabetScrollerView.kt` | Gesture interception and the fast scroller |
| `IconFactory.kt` | Mono / colour icon rendering |
| `AppPopup.kt`, `Sheets.kt`, `WidgetPickerDialog.kt` | Long-press menu, bottom sheets, widget picker |
| `SettingsActivity.kt`, `SettingsUi.kt`, `NothingSwitch.kt` | Settings screen and its components |
| `WidgetHostManager.kt` | `AppWidgetHost` plus a long-press-aware widget host view |
| `AppLauncher.kt` | Launching apps and shortcuts, uninstall, web search, system screens |

Typography: **Space Mono** and **Space Grotesk** (SIL Open Font License, see `licenses/`).
The dot-matrix type is drawn procedurally by `DotFont`.

## Build from source

```bash
./gradlew assembleDebug assembleRelease
```

APKs land in `app/build/outputs/apk/{debug,release}/`.

### Screenshot tests

```bash
./gradlew :app:recordPaparazziDebug   # regenerate app/src/test/snapshots
./gradlew :app:verifyPaparazziDebug   # fail if the UI changed unexpectedly
```

## Release timeline

| Version | Published | APK assets |
|---|---|---|
| v2 | 2026-04-08 | `app-debug.apk`, `app-release.apk` |
| v3 | 2026-04-08 | `app-debug.apk`, `app-release.apk` |
| v4 | 2026-05-25 | `app-debug.apk`, `app-release.apk` |

## Contributing

Contributions are welcome. Open an issue for bugs or ideas, then send a pull request with
clear change details. If you touch the UI, re-record the screenshots.
