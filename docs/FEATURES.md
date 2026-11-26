# Features

Not a Thing Launcher v14 pairs stock **AOSP Launcher3** (`android-14.0.0_r75`) with a responsive dot-matrix operating surface tailored for phones, tablets, foldables, and docked desk displays.

All workspace drag & drop, folder mechanics, and Android system widgets continue to work like stock Android. Historical v13, v12, and pre-v11 feature catalogs live under [`archive/`](archive/).

## Big-Screen Telemetry Studio (`w600dp`+)

- **Dual-Column Home Bento Grid:** Workspaces with 6+ columns automatically place a 2×2 studio grid (`ClockWidget` + `WeatherWidget` top, `BatteryCalendarWidget` + `DeskBriefingWidget` bottom).
- **2-Column `-1` Essential Space:** Tablet/foldable displays show the Daily Briefing + Switchboard + Vitals + Habit Matrix check-in on the left, alongside Scratchpad + Voice Memos + Focus Timer + Now Playing Media Deck on the right (`layout-w600dp/essential_space_overlay.xml`).
- **Studio Settings & Hub:** Settings expands into two columns featuring a live **Studio 14** clock & seconds preview that reacts immediately to accent/seconds toggles plus direct launch pills for **Desk HUD** and **Widget Surfaces**. The Setup Hub renders starter presets in a 2×2 Bento grid with active-preset highlight.
- **Multi-Column Smart Drawer & Search:** Minimal List expands to 2 columns (`w580dp`+) or 3 columns (`w900dp`+), Command Search cards flow into 2 columns (`w680dp`+), and bottom sheets center cleanly at up to `540dp`.

## 4-Stage Glyph Desk HUD

Launch from Customize, Drawer Header button, Desk Briefing widget, or a home gesture:

- **Four Interactive Stages:** Switch live (persisted across launches) between **COCKPIT** (all-up Bento telemetry), **CLOCK** (edge-to-edge StandBy clock theatre across Digital, Analog, Word, or World faces), **FOCUS** (distraction-free Pomodoro ring + Quick Toggles + Essential Note), and **GLYPH** (procedural LED matrix visualizer `GlyphMatrixCanvasView` reactive to active playback, Focus Timer progress, and touch ripples).
- **Desk Controls & Shortcuts:** Interactive **Torch**, **Focus Mode**, **Ringer**, and 1-tap **Habit Check-in (`LOG`/`DONE`)** toggles, **AWAKE** keep-screen-on pill, optional OLED pixel shift (`deskBurnInShift`), and hardware keyboard bindings (`1–4` stage switch, `Tab`/`M` next stage, `C` clock face, `F` focus mode, `H` log habit, `Space` play/pause or timer).

## Per-Widget Surfaces, Essential Stack & Weather Telemetry

- **Widget Surfaces (`AccentTheme.WidgetSurface`):** Override each home element independently between **Default**, **Obsidian Dark**, **Bone Paper**, and **Frosted Glass** from Customize → *Widget surfaces* or Settings → *Widget surfaces* (included in `.nothing.json` exports/imports).
- **Essential Stack (`SmartFolderWidget`):** Configure 1 app for an oversized **Max Icon** card or up to 4 apps for a classic 2×2 stack.
- **Enhanced Weather (`WeatherDotsView`):** Displays wind speed (`KM/H`) and an accent-tinted 12-hour precipitation probability baseline along the bottom of the wide sparkline.

## Visuals, Accents & Quality Tiers

- **5 Hardware Accent Palettes (`AccentTheme`):** **Nothing Red** (`#D71921`), **Warm Amber**, **Phosphor Green**, **Cobalt Blue**, and **Pure Monochrome**.
- **Interactive Dot-Grid (`DotGridDrawable`), Icons & Physics:** Dot grid / solid / wallpaper backdrops with parallax light pool, empty-space ripples, Night-dimming, Focus Mode attenuation, Monochrome / Colour / **13×13 Dot-Matrix Glyph** icons + ADW/Nova icon packs, `SpringAnimation` rebound + optional 4 ms mechanical click audio, and automatic **Lite / Balanced / Full** `VisualTier` scaling.

## `-1` Essential Space Overlay

Swipe right from Home page 0 to reveal Daily Briefing (greeting, weather pill, alarm & calendar event), Hardware Switchboard, Scratchpad Note & local `.m4a` Voice Memos, Focus Timer / Stopwatch, Device Vitals, plus Tablet Habit Matrix check-in and Now Playing Media Deck.

## 12 Dot-Matrix Widgets

Available under *Not a Thing* in `Customize → Widgets`:

1. **Clock (`ClockWidget`)** — Digital (12/24h + seconds), Analog Dot-Matrix, Editorial Word Clock, and World Clock (`worldClockZoneId`).
2. **Battery & Calendar (`BatteryCalendarWidget`)** — Full-width Battery dot ring + charging bolt and Calendar card (empty alarm row auto-hidden).
3. **Weather (`WeatherWidget`)** — Live Open-Meteo conditions, 12h temperature sparkline, hourly rain baseline, & wind speed.
4. **Media Player (`MediaWidget`)** — Track/artist, transport controls, audio route, and Bayer 4×4 halftone album art.
5. **Hardware Toggles (`QuickTogglesWidget`)** — Torch, Focus Mode, Caffeine, and Ringer cycle.
6. **Focus Timer (`FocusTimerWidget`)** — `DotRingGaugeView` Pomodoro countdown (`5/10/15/25/45M`) & stopwatch.
7. **Essential Note (`EssentialNoteWidget`)** — Scratchpad synced across Home, Essential Space, and Desk HUD.
8. **Habit Matrix (`HabitMatrixWidget`)** — 7×7 dot streak grid with 1-tap check-in and editable habit title.
9. **Device Vitals (`VitalsWidget`)** — Live Storage, RAM, and optional daily screen time.
10. **Compass & Steps (`CompassStepWidget`)** — Visibility-gated azimuth dial & step counter.
11. **Essential Stack (`SmartFolderWidget`)** — Single oversized Max Icon tile or 2×2 quick-launch stack.
12. **Desk Briefing (`DeskBriefingWidget`)** — Greeting, weather, next calendar/alarm briefing, live Focus Timer pill, and 1-tap **ENTER DESK HUD** launch.

## Offline Command Search, 3-Mode Smart Drawer & Hidden Vault

- **Command Search (`CommandEngine`):** Press `/` or Search on Home (or tap the drawer bar) for offline math & percentages, unit/temperature conversions, city timezones, hex/named color inspection, utilities (`uuid`, `dice`, `coin`, palette), quick actions (`timer 25m`, `note ...`, `preset ...`, `desk`), contacts, web search, and acronym/1-typo fuzzy app matching.
- **3-Mode Smart Drawer (`DrawerHeader` + `NothingAllAppsContainerView` + `AppCategorizer`):** Cycle **GRID → LIST → CATS** directly in the drawer header (or Settings) to switch between stock icon grid, responsive multi-column minimal list, and categorized dot-section groups, alongside the recent apps strip and biometric [`HiddenVaultSheet`](../app/src/main/java/com/nothinglauncher/drawer/HiddenVaultSheet.kt).
