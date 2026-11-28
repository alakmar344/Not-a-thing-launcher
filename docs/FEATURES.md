# Features

Not a Thing Launcher **v15.6** pairs stock **AOSP Launcher3** (`android-14.0.0_r75`) with a deeply hardened, compact dot-matrix operating surface featuring biometric-locked & AES-256-GCM encrypted backup export/import, hardened Hidden Vault privacy across notifications, media sessions, recents, and multi-display boundaries, and optimized release APK/AAB footprint across Android 8.0 – 17.

All workspace drag & drop, folder mechanics, and Android system widgets continue to work like stock Android. Historical v15, v13, v12, and pre-v11 feature catalogs live under [`archive/`](archive/).

## v15.6 Hardened Security, Locked Backups & Leaner App Bundle

- **Zero-Allocation Dot Rendering & Physics:** Precomputed bezel/ring trig tables, view-size geometry caches, boolean dot/habit masks, reusable scratch `Calendar`s, quantized backdrop ripple updates, squared-distance physics culling, and byte-grid Snake rendering across `DotMatrixView`, `AnalogDotClockView`, `DotRingGaugeView`, `BatteryDotsView`, `WeatherDotsView`, `MediaDotsView`, `HabitPegboardView`, `KineticMatrixView`, `GlyphMatrixCanvasView`, and `DotGridDrawable`.
- **Fast Icon Pipeline & Smart Memory Trimming:** `IconFactory` reuses thread-local scratch bitmaps/paints/pixel buffers (`ThreadScratch`) and static `ColorFilter`s, adapts LRU budgets to `VisualTier`, exposes `getCached()` fast-paths so Drawer rows/Recents skip Binder IPC when cached, and coordinates multi-level `onTrimMemory()` eviction across `IconFactory`, `DotFont`, `AppCategorizer`, `IconPackManager`, `NotificationDigestRepository`, and `HardwareControlRepository`.
- **Responsive Home, Drawer, Search & `-1` Dock Space:** Zero-alloc home gesture hit-testing, ViewHolder-cached drawer list/command/category bindings with span-index & group caching, zero-alloc `CommandEngine.scoreApp` + lazy timezone index, event-driven overlay/HUD tickers that sleep when timers/recording are idle, hardware-layer slide compositing, and AOT ART compilation via `baseline-prof.txt`.

## Phone-First Experiences

- **Android System StandBy Screensaver (`NothingDreamService`):** Enable *StandBy Dot Clock* from Settings → **StandBy screensaver** (or Android Screen saver settings) to automatically display a low-glare OLED dot-matrix clock with per-minute burn-in pixel shift, charging battery percentage, weather/alarm/media line, Notification Digest status, and a 1-tap **DESK HUD** launch button whenever your phone is docked or charging.
- **Kinetic Matrix Widget (`KineticMatrixWidget`):** Interactive `16×10` LED dot playground right on your Home screen with three switchable modes — accelerometer tilt-driven **GRAVITY** beads + finger attract, tap-to-seed Conway's **LIFE**, and playable Dot **SNAKE** (with committed-tick anti-reversal input protection).
- **On-Device Notification Digest (`NotificationDigestRepository`):** Live inside `-1` Dock Space (`spaceDigestCard`) and StandBy Dream — groups and prioritizes active notifications on-device via AOSP `NotificationListener`, opens threads/apps in 1 tap (Android 14+ BAL-safe), clears state immediately if notification access is revoked, and shields low-priority alerts (`FOCUS SHIELD ACTIVE`) whenever Focus Mode is enabled.
- **Procedural Focus Soundscapes (`FocusSoundEngine`):** Zero-asset stereo `AudioTrack` synthesizer built into `-1` Dock Space and the Matrix Desk HUD (`NOISE: OFF` → `BROWN` → `RAIN` → `40HZ`), synced live with Desk HUD's Matrix visualizer pulses.

## Big-Screen Telemetry Studio (`w600dp`+)

- **Dual-Column Home Bento Grid:** Workspaces with 6+ columns automatically place a 2×2 studio grid (`ClockWidget` + `WeatherWidget` top, `BatteryCalendarWidget` + `DeskBriefingWidget` bottom).
- **2-Column `-1` Dock Space:** Tablet/foldable displays show the Daily Briefing + Switchboard + Vitals + Habit Matrix check-in on the left, alongside Scratchpad + Voice Memos + Focus Timer + Now Playing Media Deck on the right (`layout-w600dp/essential_space_overlay.xml`).
- **Studio Settings & Locked Backups:** Settings expands into two columns featuring an inline **5-dot Hardware Accent Swatch row**, synced Icon Aesthetic controls, a live **Studio 15.6** clock & seconds preview, direct launch pills for **Desk HUD** and **Widget Surfaces**, and locked **Export JSON** / **Import JSON** flows (`not-a-thing-v15.6-setup.json`). Exporting and accepting backup data require [`DeviceAuthGate`](../app/src/main/java/com/nothinglauncher/security/DeviceAuthGate.kt) authentication, strip hidden apps from cleartext JSON, optionally encrypt Hidden Vault payloads with **AES-256-GCM + PBKDF2**, and confirm data before applying in `FLAG_SECURE` dialogs.
- **Multi-Column Smart Drawer & Search:** Minimal List expands to 2 columns (`w580dp`+) or 3 columns (`w900dp`+), Command Search cards flow into 2 columns (`w680dp`+), and bottom sheets center cleanly at up to `540dp`.

## 4-Stage Matrix Desk HUD

Launch from Customize, Drawer Header button, Desk Briefing widget, or a home gesture:

- **Four Interactive Stages:** Switch live (persisted across launches) between **COCKPIT** (all-up Bento telemetry), **CLOCK** (edge-to-edge StandBy clock theatre across Digital, Analog, Word, or World faces), **FOCUS** (distraction-free Pomodoro ring + Quick Toggles + Focus Note), and **MATRIX** (procedural LED visualizer `GlyphMatrixCanvasView` reactive to active playback, Focus Timer progress, and touch ripples).
- **Desk Controls & Shortcuts:** Interactive **Torch**, **Focus Mode**, **Ringer**, and 1-tap **Habit Check-in (`LOG`/`DONE`)** toggles, **AWAKE** keep-screen-on pill, optional OLED pixel shift (`deskBurnInShift`), and hardware keyboard bindings (`1–4` stage switch, `Tab`/`M` next stage, `C` clock face, `F` focus mode, `H` log habit, `Space` play/pause or timer).

## Per-Widget Surfaces, App Stack & Weather Telemetry

- **Widget Surfaces (`AccentTheme.WidgetSurface`):** Override each home element independently between **Default**, **Obsidian Dark**, **Bone Paper**, and **Frosted Glass** from Customize → *Widget surfaces* or Settings → *Widget surfaces* (included in JSON backup exports/imports, with live widget auto-sync).
- **App Stack (`SmartFolderWidget`):** Configure 1 app for an oversized **Max Icon** card or up to 4 apps for a classic 2×2 stack (supports icon packs & cached icons).
- **Enhanced Weather (`WeatherDotsView`):** Displays wind speed (`KM/H`) and an accent-tinted 12-hour precipitation probability baseline along the bottom of the wide sparkline, aligned to the forecast location's local timezone.

## Visuals, Accents & Quality Tiers

- **5 Hardware Accent Palettes (`AccentTheme`):** **Signal Red** (`#D71921`), **Warm Amber**, **Phosphor Green**, **Cobalt Blue**, and **Pure Monochrome**.
- **Interactive Dot-Grid (`DotGridDrawable`), Icons & Physics:** Dot grid / solid / wallpaper backdrops with parallax light pool, empty-space ripples, Night-dimming, Focus Mode attenuation, Pure Monochrome / Original Colour / **13×13 Dot-Matrix Symbols** icons + ADW/Nova icon packs, `SpringAnimation` rebound + optional 4 ms mechanical click audio (cached HAL-safe static buffer), and automatic **Lite / Balanced / Full** `VisualTier` scaling (honouring system reduced-motion & Battery Saver across all animators).

## `-1` Dock Space Overlay

Swipe right from Home page 0 to reveal Daily Briefing (greeting, weather pill, alarm & calendar event), Hardware Switchboard, Scratchpad Note & local `.m4a` Voice Memos, Focus Timer / Stopwatch + **Procedural Soundscapes**, **On-Device Notification Digest**, Device Vitals, plus Tablet Habit Matrix check-in and Now Playing Media Deck (with full card-area swipe-to-dismiss and strict listener teardown on close).

## 13 Dot-Matrix Widgets

Available under *Not a Thing* in `Customize → Widgets`:

1. **Clock (`ClockWidget`)** — Digital (12/24h + seconds), `AnalogDotClockView`, editorial `WordClockFormatter`, and live-timezone World Clock (`worldClockZoneId` rendered directly on the main dot matrix with local time in the header pill).
2. **Battery & Calendar (`BatteryCalendarWidget`)** — Full-width Battery dot ring + charging bolt and Calendar card (empty alarm row auto-hidden).
3. **Weather (`WeatherWidget`)** — Live Open-Meteo conditions, 12h temperature sparkline, hourly rain baseline, & wind speed.
4. **Media Player (`MediaWidget`)** — Track/artist, transport controls, audio route, and Bayer 4×4 halftone album art.
5. **Hardware Toggles (`QuickTogglesWidget`)** — Torch, Focus Mode, Caffeine, and Ringer cycle.
6. **Focus Timer (`FocusTimerWidget`)** — `DotRingGaugeView` Pomodoro countdown (`5/10/15/25/45M`) & stopwatch.
7. **Focus Note (`EssentialNoteWidget`)** — Scratchpad synced across Home, Dock Space, and Desk HUD.
8. **Habit Matrix (`HabitMatrixWidget`)** — 7×7 dot streak grid with 1-tap check-in and editable habit title.
9. **Device Vitals (`VitalsWidget`)** — Live Storage, RAM, and optional daily screen time.
10. **Compass & Steps (`CompassStepWidget`)** — Visibility-gated azimuth dial & step counter.
11. **App Stack (`SmartFolderWidget`)** — Single oversized Max Icon tile or 2×2 quick-launch stack.
12. **Desk Briefing (`DeskBriefingWidget`)** — Greeting, weather, next calendar/alarm briefing, live Focus Timer pill, and 1-tap **ENTER DESK HUD** launch.
13. **Kinetic Matrix (`KineticMatrixWidget`)** — Live `GRAVITY` tilt beads, Conway's `LIFE`, and playable Dot `SNAKE` on Home.

## Offline Command Search, 3-Mode Smart Drawer & Hidden Vault

- **Command Search (`CommandEngine`):** Press `/` or Search on Home (or tap the drawer bar) for offline math & percentages, unit/temperature conversions, city timezones, hex/named color inspection, utilities (`uuid`, `dice`, `coin`, palette), quick actions (`timer 25m`, `note ...`, `preset ...`, `desk`), contacts, web search, and acronym/1-typo fuzzy app matching (sequence-guarded against out-of-order async results, with Enter launch on both grid icons and list rows).
- **3-Mode Smart Drawer (`DrawerHeader` + `NothingAllAppsContainerView` + `AppCategorizer`):** Cycle **GRID → LIST → CATS** directly in the drawer header (or Settings) to switch between stock icon grid, responsive multi-column minimal list, and categorized dot-section groups with rebuilt fast-scroller indices, alongside the recent apps strip and fail-closed `FLAG_SECURE` biometric/credential [`HiddenVaultSheet`](../app/src/main/java/com/nothinglauncher/drawer/HiddenVaultSheet.kt) (`VaultAuthActivity` support across Android 8–17, with hidden apps isolated from notifications, media sessions, recents, and disabled on secondary displays).
