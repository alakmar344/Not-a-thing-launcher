# Features

Not a Thing Launcher v13 pairs stock **AOSP Launcher3** (`android-14.0.0_r75`) with a cohesive Nothing OS–inspired dot-matrix surface, interactive telemetry, the `-1` Essential Space hub, offline Command Search, and granular personalization.

All workspace drag & drop, folder mechanics, and Android system widgets continue to work like stock Android. For the historical v12 and pre-v11 feature catalogs, see [`archive/`](archive/).

## Visuals, Accents & Physics

- **5 Hardware Accent Palettes (`AccentTheme`):** Switch live in Settings → Personality & Physics between **Nothing Red** (`#D71921`), **Warm Amber**, **Phosphor Green**, **Cobalt Blue**, and **Pure Monochrome**.
- **Interactive Dot-Grid Backdrop (`DotGridDrawable`):** Dot grid, solid black/bone, or live wallpaper. In Dots mode, a subtle light pool shifts smoothly across workspace pages and drawer transitions, empty-space taps emit an accent-tinted dot ripple (`BALANCED`/`RICH` tiers), and **Night-dim dots** / **Focus Mode** soften ambient dot brightness after 22:00.
- **Icons & Icon Packs (`IconFactory` + `IconPackManager`):** Pick **Nothing Monochrome**, **Original Colour**, or **13×13 Dot-Matrix Glyphs**, or layer installed third-party ADW/Nova/Apex icon packs on top of any style.
- **Spring Physics & Mechanical Audio (`Tactile` + `TactileAudio`):** Every widget tile, Customize chip, and Drawer card uses `SpringAnimation` press/rebound with haptic ticks and an optional synthesized 4 ms mechanical click sound.
- **Space Grotesk Typography & Contrast:** Unified typography with crisp contrast in both dark and light modes (`@color/pill_active_text` on active pills and dialogs).

## Visual Quality (Fits the Phone)

Settings → Look → **Visual quality**: *Auto* (default), *Lite*, or *Full*. Auto selects from device RAM and CPU cores:

| Tier | Picked for | What changes |
|---|---|---|
| **Lite** | up to 4 GB RAM (incl. 3.5 GB), low-RAM flag, or ≤ 4 cores | Flat dot grid (no vignette/light pool/ripples), no LED glow, no power-on sweep or settings entrance, slower visualiser, charging pulse redrawn ~8×/s, widget picker released immediately when backgrounded |
| **Balanced** | 6 GB-class phones | Edge vignette, LED glow, power-on clock sweep, empty-space dot ripples, settings entrance |
| **Full** | 8 GB+ phones with 6+ cores | Everything above plus top light pool, sheet blur (Android 12+), and full frame-rate visualiser |

All decorative motion pauses automatically under Battery Saver or disabled system animations. On Android 11+, if the OS terminates home under memory pressure (`ApplicationExitInfo` / Android 17 memory limits), Auto automatically switches to `Lite` and reports it in Settings.

## `-1` Essential Space Overlay

Swipe right from Home page 0 (or trigger via Customize sheet / gesture) to reveal the in-process **Essential Space** without background services:

- **Daily Briefing:** Time-of-day greeting, Open-Meteo weather summary pill, next alarm time, and optional next 48h calendar event (`READ_CALENDAR`).
- **Hardware Switchboard:** Quick tiles for Camera Torch, Focus Mode (mutes home accents), Caffeine (keeps screen awake), and Ringer cycle (Normal / Vibrate / Silent).
- **Quick Capture & Voice Memos:** Instant shared scratchpad note editor synced with the Essential Note home widget, plus local `.m4a` voice recording and playback (`RECORD_AUDIO`).
- **Live Focus Timer / Stopwatch:** Real-time countdown & stopwatch readout synced 1:1 with the home Focus Timer widget.
- **Device Vitals:** Free/total storage, available/total RAM, and optional daily foreground screen time (`PACKAGE_USAGE_STATS`).

## 11 Dot-Matrix Widgets

Available under *Not a Thing* in the stock widget picker (`Customize → Widgets`), with Android S+ preview cards and descriptions:

| # | Widget | What it does |
|---|---|---|
| 1 | **Clock (`ClockWidget`)** | Tap bottom pill to cycle Digital (12/24h + optional seconds), Analog Dot-Matrix, Editorial Word Clock, and World Clock (`worldClockZoneId`) |
| 2 | **Battery & Calendar (`BatteryCalendarWidget`)** | Dot battery arc with charging bolt badge, today's date (links to Calendar), and next alarm row (hidden cleanly when no alarm is set) |
| 3 | **Weather (`WeatherWidget`)** | Live Open-Meteo conditions (IP fallback or manual city; zero fetches unless on Home) with compact `2×2` and wide `4×2+` 12-hour temperature dot-sparkline + wind speed |
| 4 | **Media Player (`MediaWidget`)** | Track/artist, transport controls, audio output route (`BT`, `WIRED`, `SPEAKER`), and background 10×10 Bayer 4×4 halftone album art |
| 5 | **Hardware Toggles (`QuickTogglesWidget`)** | Home-screen toggles for Torch, Focus Mode, Caffeine, and Ringer mode |
| 6 | **Focus Timer (`FocusTimerWidget`)** | Circular dot-ring Pomodoro timer & stopwatch (`5/10/15/25/45` min presets) synced with Essential Space |
| 7 | **Essential Note (`EssentialNoteWidget`)** | Always-visible scratchpad synced with Essential Space |
| 8 | **Habit Matrix (`HabitMatrixWidget`)** | 7×7 dot streak calendar with 1-tap daily check-off and editable title |
| 9 | **Device Vitals (`VitalsWidget`)** | Live Storage & RAM dot gauges + optional daily screen time |
| 10 | **Compass & Steps (`CompassStepWidget`)** | Magnetometer heading dot dial and step counter with strict visibility sensor gating |
| 11 | **Essential Stack (`SmartFolderWidget`)** | Interactive 2×2 quick-launch stack with custom title |

A minimal `DateTimeWidgetProvider` (`AppWidgetProvider`) remains bundled so Launcher3 always detects a registered system widget provider.

## Offline Command Search, Smart Drawer & Hidden Vault

- **Command Search (`CommandEngine`):** Evaluates math & percentages (`(12 + 8) * 3`, `15% of 240`), metric/imperial/temperature conversions (`100 km to mi`, `25 c to f`), city timezones (`time tokyo`), device toggles (`torch`, `focus`, `caffeine`, `settings`), optional contacts search (`READ_CONTACTS`), DuckDuckGo web search, and acronym/initials (`yt` → YouTube) + 1-typo fuzzy app scoring.
- **Smart Drawer (`DrawerHeader` + `NothingAllAppsContainerView`):** Live app count, 1-tap `GRID` / `LIST` drawer switcher, recent-apps strip (up to 5 recent launches), Vault button, and Settings shortcut.
- **Hidden Vault & Long-Press Shortcuts (`HiddenVaultSheet`, `NothingShortcuts`):** Long-press any app icon for *Hide in Vault* (removes from drawer and search until unlocked with biometrics/device credential or unhiding) or *Rename* (per-app custom label across home and drawer).

## Gestures, Presets, Backup & Setup Hub

- **Home Gestures & Cleanliness:** Configure Swipe Down, Double-Tap empty space, and 2-finger Pinch-in (Expand Notifications, Command Search, Essential Space, Customize Sheet, Hidden Vault, or biometric-safe Screen Lock via optional [`NothingLockAccessibilityService`](../app/src/main/java/com/nothinglauncher/NothingLockAccessibilityService.kt)); toggle home icon labels and hotseat search pill visibility.
- **Curated Presets & `.nothing.json` Backup (`SetupBackupManager`):** Apply *Telemetry v13*, *Monolith Minimal*, *Command & Focus*, or *Studio Media* in one tap, or export/import complete configuration, notes, habits, labels, and vault state via `.nothing.json` files.
- **Setup & Permissions Hub (`LauncherEntryActivity`):** Launched from the drawer icon; checks Default Home status, lets users apply presets or export backups, and surfaces live `ACTIVE` / `ENABLE` status for all optional permissions.
