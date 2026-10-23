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

### Multi-Page Workspace & Dynamic Home
- **Horizontally paginated workspace** — swipe left and right between customizable home screen pages.
- **Minimalist dot page indicator** — active page dot elongates smoothly, tap to jump to any page.
- **Dynamic page management** — add blank pages or remove pages directly from *Customize*.
- **Move apps across pages** — long-press an app on home to move it to the next or previous page.
- **Max Icons (Enlarge 2×2)** — expand any app icon into a prominent 2×2 hero tile, matching Nothing OS 2.5/3.0.
- **Workspace App Folders** — group apps into clean Nothing dot folders with mini-icon previews and full folder manager modal.

### Bespoke Nothing Elements
- **LED-panel clock** — the time is lit on a full grid of unlit dots, like a real dot-matrix
  display. The colon blinks once per second in the accent colour, and a 60-dot **seconds
  ticker** runs underneath. Follows the system 12/24-hour setting. Tap to open alarms.
- **Battery tile** — a ring of 40 dots with the percentage in dot-matrix type. It turns red
  at ≤15%, and the next dot breathes while charging. Tap for battery usage.
- **Calendar tile** — month, dot-matrix day number, weekday and your next alarm. Tap to
  open your calendar.
- **Weather tile** — live dot-matrix temperature, procedural sun & cloud graphics, conditions, and high/low ranges. Tap for weather details.
- **Quick toggles tile** — 4 system controls (Wi-Fi, Bluetooth, Flashlight/Torch with camera hardware control, and Sound mode cycler).
- **Step counter (Pedometer)** — 36-dot progress ring tracking daily steps with live count in dot-matrix typography.
- **Heading compass** — procedural dot-matrix compass rose with accent North indicator and degrees heading.
- **Screen Time tile** — digital wellbeing daily active time in dot-matrix typography with a 7-segment dot usage bar.
- **Storage & RAM tile** — device capacity meter with DotFont free GB readout and circular dot fullness ring.
- **World Clock tile** — dual time-zone dot clock showing local and UTC/London times.
- **Battery Gauge tile** — full-width live battery % readout in DotFont with a 28-dot circular capacity meter and charging bolt indicator.
- **Quick Note / Quote tile** — customizable dot-matrix scratchpad on your home screen; tap to edit note or inspirational quote with live dot accent.
- **Third-party widgets** — add any standard Android widget to any page. Long-press to remove.

### Theming & Appearance (Dark & Light Mode)
- **Dark & Light Mode** — toggle between pure OLED dark (`#000000`), clean bone-white light mode (`#F6F6F4` with jet-black ink), or follow system default.
- **Dynamic Contrast Bars** — status bar and navigation bar contrast adapt dynamically across themes.
- **Nothing Monochrome Icon Engine** — converts third-party apps into high-contrast Nothing OS monochrome discs.
- **Icon Sizing** — small (80%), standard (100%), or large (120%) icon scales.
- **App Labels & Grid** — separate toggles for home and dock labels; 3, 4, or 5 column grids.

### Gestures & Navigation
- **Double-Tap to Sleep** — double-tap any empty workspace area to turn off the screen.
- **Swipe-Down Action** — configurable action to pull down notification shade, open search, or open quick settings.
- **Follows your finger drawer** — fluid drawer drag physics with background blur.
- **Ranked search** — prefix, then word-start, then substring, then initials ("gm" → Google
  Maps). Press enter to open the top hit. No match offers a one-tap web search.
- **Recent apps** row, **A–Z dot scroller** with a dot-matrix letter bubble, and
  **hidden apps** (restore them in Settings).

### Long-Press Home Screen & App Menus
- **Home Screen Long-Press Menu** — long-pressing any empty space triggers tactile haptic feedback and displays the **Home Customisation** sheet:
  - **Wallpaper & Style** — direct access to system wallpaper picker, Nothing Dot Matrix background, or Solid Minimalist (pure OLED black / clean white).
  - **Widgets & Bespoke Elements** — opens the categorized Nothing Elements & System AppWidgets library.
  - **Page Manager** — 1-tap to add new workspace pages or remove current page.
  - **Home Settings** — instant access to launcher settings.
- **App Long-Press Actions** — 2×2 Hero Enlarge/Shrink, Add to Folder / Create Folder, App Info (deep link to Android settings), Uninstall (with system prompt), Pin to Home, Dock/Undock, and dynamic App Shortcuts.
- **Haptic Feedback Engine** — tactile ticks when scrubbing the A–Z alphabet drawer, snapping pages, tapping quick toggles, or opening folders.



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
| `HomeFragment.kt` | Multi-page horizontal workspace (`workspaceRecycler` + `PagerSnapHelper`), page indicator, dock, swipe gestures |
| `WorkspacePageAdapter.kt` | Dynamically renders per-page elements, 3rd-party widgets, and drag-and-drop app grids |
| `WorkspacePage.kt` | Data model for multi-page workspace and BespokeElement registry |
| `PageIndicatorView.kt` | Minimalist Nothing-style animated dot page indicator |
| `WeatherDotsView.kt` | Procedural dot-matrix weather tile (temperature, sun/cloud icon, conditions) |
| `PedometerDotsView.kt` | Circular dot activity ring with dot-matrix step count |
| `CompassDotsView.kt` | Cardinal compass dial with North accent dot and degrees |
| `AppDrawerFragment.kt` | Drawer list, search ranking, recents, A–Z scroller, keyboard |
| `LauncherViewModel.kt` | Live package callbacks, multi-page workspace mutations, elements & widget binding, defaults |
| `LauncherPrefs.kt` | Typed settings + multi-page JSON layout persistence |
| `DotFont.kt`, `DotMatrixView.kt`, `DotMatrixClockView.kt` | Hand-built 5×7 dot-matrix typeface and LED-panel renderer |
| `BatteryDotsView.kt`, `SecondsTickerView.kt`, `DotGridDrawable.kt` | Custom-drawn widgets and background |
| `SwipeGestureLayout.kt`, `AlphabetScrollerView.kt` | Gesture interception and the fast scroller |
| `IconFactory.kt` | Mono / colour icon rendering |
| `AppPopup.kt`, `Sheets.kt`, `WidgetPickerDialog.kt` | Long-press menu, bottom sheets, elements and widget picker |
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
| v5 | 2026-09-10 | `app-debug.apk`, `app-release.apk` |
| v6 | 2026-10-23 | `app-debug.apk`, `app-release.apk` |
| v7 | 2026-10-23 | `app-debug.apk`, `app-release.apk` |

## Contributing

Contributions are welcome. Open an issue for bugs or ideas, then send a pull request with
clear change details. If you touch the UI, re-record the screenshots.
