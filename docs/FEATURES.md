# Features

Not a Thing is **stock Android Launcher3** with the Not a Thing visual design on top. Home
screen, app drawer, folders, widgets, drag & drop, app shortcuts and settings all behave exactly
like stock Android/Launcher3 — this page only lists what the Not a Thing layer (`app/`) adds or
changes. For what "stock Launcher3 behaviour" means in practice, see the
[AOSP Launcher3 docs](https://android.googlesource.com/platform/packages/apps/Launcher3/) or
just use it like any Pixel-style launcher.

## Visuals

- **Fixed monochrome palette** (black/bone + Nothing red `#D71921`) replacing Launcher3's
  wallpaper-derived Material You colours, applied everywhere: home, all-apps, folders, popups,
  the widget picker and settings.
- **Space Grotesk** typography and red notification dots.
- **Icons** rendered as the app's glyph in a flat disc (monochrome) or the original artwork in a
  circle (colour) — toggle in Settings → Look (follows Launcher3's themed-icons setting).
- **Backdrop**: dot-grid, solid black/bone, or the system wallpaper (Settings → Look →
  Background).

## Home screen

- First run places two Not a Thing widgets automatically: the LED clock across the top and a
  battery/calendar tile below it (if the grid has room).
- Long-press empty space opens the Not a Thing **Customize sheet** (Wallpaper & style, Widgets,
  Settings) instead of Launcher3's stock long-press menu.
- Everything else on the home screen (pages, folders, Max/enlarged icons, drag & drop,
  resizing, the dock) is stock Launcher3.

## Dot-matrix widgets

Found in the stock widget picker under *Not a Thing* (long-press home → Widgets, or Customize
sheet → Widgets). Added, moved, resized and removed exactly like any other Android widget:

| Widget | What it shows |
|---|---|
| **LED Clock** | Dot-matrix time + date, optional live seconds ticker (Settings → Home) |
| **Battery & Calendar** | Battery dot gauge next to day/weekday and the next system alarm |
| **Weather** | Live conditions from Open-Meteo (no API key); manual city or automatic lookup from the public IP (ipapi.co, falling back to ipwho.is) (Settings → Permissions & capabilities → Weather location). Nothing is fetched unless a weather element is on a home screen |
| **Media** | Now-playing track/artist with play/pause/next/previous, once notification access is granted |

A plain system "Date & time" widget is also available — it exists mainly so the device always
has at least one installed widget provider (Launcher3 needs that to register the in-process
widgets above).

## App drawer & search

Stock Launcher3: search, alphabetical scrubber, hidden apps, app shortcuts via long-press — all
unmodified, just restyled in the Not a Thing palette.

## Settings

Not a Thing's own settings screen (long-press home → Settings, or the app icon's shortcut)
groups:

- **Home** — grid size (3×3/4×4/5×5 on phones), seconds ticker, "add icon to home screen on
  install", auto-rotate.
- **Look** — icon style (mono/colour), background (dots/black/wallpaper), wallpaper picker.
- **Permissions & capabilities** — notification access (needed for the media widget and
  notification dots), weather location.
- **System** — default-launcher status/shortcut, **Advanced home settings** (opens Launcher3's
  own stock settings screen, e.g. notification dots), and About.

Grid size, icon style and rotation are read from and written straight to Launcher3's own
`LauncherPrefs`/`InvariantDeviceProfile`, so they always match what Launcher3's stock settings
screen (reachable via "Advanced home settings") shows.

## App icon & onboarding

Unlike a raw home-only activity, Not a Thing has a real app icon (an invisible trampoline
activity). Tapping it shows a first-run welcome screen with "Set as default home", or opens the
system's home-app chooser / straight to the home screen if it's already default. Long-pressing
the icon offers "Launcher settings" and "Set as default home app" shortcuts.

## Updates

Every release is signed with one stable key and uses a monotonically increasing `versionCode`,
so installing a new APK over an old one updates in place instead of requiring an uninstall. See
the root [README](../README.md#release-signing--update-compatibility) for details.
