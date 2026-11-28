# Changelog

All notable changes to Not a Thing Launcher. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Dates are release dates, not commit
dates.

## [Unreleased]

## [15.5] — Deep Performance, Allocation, Memory, IPC & Binary Size Overhaul

### Added
- **ART Baseline Profile (`app/src/main/baseline-prof.txt`):** Added targeted startup (`NothingApplication`, `NothingLauncher`, `NothingPrefs`, `DotMatrix*`, `IconFactory`, Launcher3 core `Launcher`/`Workspace`/`CellLayout`/`LoaderTask`) and hot `onDraw`/`onMeasure` rules so ART compiles launch, draw, overlay, and scroll paths AOT across Android devices (`assets/dexopt/baseline.prof` `2,028` B -> `2,160` B; `baseline.profm` `215` B -> `275` B).
- **Tests:** Added [`V15_5OptimizationTest.kt`](app/src/test/java/com/nothinglauncher/V15_5OptimizationTest.kt) (8 tests) verifying `NothingPrefs` cached collection hits/invalidations, `WeatherRepository.cached()` parsed snapshot reuse, bit-for-bit parity of zero-allocation `FocusSoundEngine.fillBuffer()` vs `synthesizeSample()`, all `CommandEngine.scoreApp()` fuzzy/acronym tiers, `IconFactory` cache hits + `TRIM_MEMORY_RUNNING_LOW` eviction, precomputed custom view rendering (`GRAVITY`/`LIFE`/`SNAKE`, `AnalogDotClockView`, `DotRingGaugeView`, `BatteryDotsView`, `WeatherDotsView`, `MediaDotsView`, `SecondsTickerView`, `HabitPegboardView`, `GlyphMatrixCanvasView`), and `HardwareControlRepository.readVitals()` TTL caching.
- **v15 Docs Archive:** Preserved v15 release notes, architecture, and feature catalog under [`docs/archive/`](docs/archive/README.md) (`RELEASE_V15.md`, `ARCHITECTURE.v15.md`, `FEATURES.v15.md`).

### Changed
- **Version 15.5 Identity:** Bumped `versionName` to `"15.5"` (`app/build.gradle`), setup backup filename (`not-a-thing-v15.5-setup.json`), Setup Hub (`NOT A THING 15.5`), Preset label (`Telemetry v15.5`), and Settings **Studio 15.5** hero (`NothingSettingsActivity`, updated `V15FeaturesTest`).
- **In-Memory Caching Across Prefs & Repositories:**
  - `NothingPrefs`: Added `ParsedCache` snapshots for `habitDates`, `hiddenPackages`, `customAppLabels`, `recentComponents`, `quickFolderApps`, and `allWidgetSurfaces()` so repeated UI binds/draws do zero repeat JSON parsing; optimized `recordAppLaunch()` to short-circuit when already at index 0.
  - `WeatherRepository`: Cached parsed `Weather` snapshot by raw JSON string so repeat widget/overlay/Dream binds avoid `JSONObject` allocation.
  - `HardwareControlRepository`: Added context-scoped monotonic TTL caching (`5s` vitals, `45s` `UsageStatsManager` screen time, `30s` calendar events) and synchronized midnight `Calendar` reuse.
  - `NotificationDigestRepository`: Hoisted precompiled `WHITESPACE_RE` and added bounded per-package app-label caching with `trimCaches()`.
- **Icon Pipeline & Memory Coordination (`IconFactory`, `IconPackManager`, `NothingApplication`):**
  - Eliminated per-icon temporary `Bitmap`, `Paint`, `Path`, `IntArray`, and `ColorMatrix` allocations via `ThreadLocal<ThreadScratch>` buffers, shared immutable `ColorFilter` singletons, and precomputed 13×13 `DOT_MASK_13`.
  - Added `IconFactory.configureForTier()` (`maxMemory / 20` on `LITE` vs `maxMemory / 12` elsewhere), tiered `trimMemory(level)`, and `IconFactory.getCached()` fast-paths used by `NothingSearchAdapterProvider` and `DrawerHeader` before triggering `IconPackManager` / `PackageManager` IPC.
  - Replaced `XmlPullParserFactory.newInstance()` with `android.util.Xml.newPullParser()` and cached pack `Resources` in `IconPackManager`; expanded `NothingApplication.onTrimMemory()` to evict `AppCategorizer`, `IconPackManager`, `NotificationDigestRepository`, and `HardwareControlRepository` caches under pressure.
- **Zero-Allocation Custom Views, Physics & Audio (`onDraw` & DSP):**
  - Precomputed trigonometry lookup tables and view-dimension geometry caches in `AnalogDotClockView`, `DotRingGaugeView` (plus cached `breakText` subtitle), `BatteryDotsView`, `WeatherDotsView` (plus cached `breakText` fits & allocation-free 12h sparkline), `MediaDotsView` (plus LCG step), `GlyphMatrixCanvasView` (eliminating per-frame lattice `hypot` & ring `Math.toRadians` calls), `HabitPegboardView` (28-day cached boolean mask), `DotMatrixView` (flat `BooleanArray` accent mask & single-line fast path), `DotMatrixClockView` & `SecondsTickerView` (reused `Calendar`), `DotGridDrawable` (quantized ripple updates + shared tile `Paint`), `KineticMatrixView` (`snakeDrawFlags` `ByteArray(160)` eliminating 160 `Pair` allocations/frame, cached display rotation, squared-distance collision cull before `sqrt`, unrolled toroidal Life neighbor count).
  - Replaced per-sample `Pair<Float, Float>` boxing in `FocusSoundEngine` streaming loop with inline primitive `fillBuffer()`.
- **UI Smoothness, Search & Touch Paths:**
  - `NothingLauncher`: Reused `IntArray(2)`/`Rect` in `HomeGestureController.hitContains()` and cached `DrawerHeader`/`HotseatSearchPill` lookups.
  - `EssentialSpaceOverlay`: Halted background dynamic counter tickers when neither timer nor recording is active, enabled temporary `LAYER_TYPE_HARDWARE` during open/close slide/settle transitions, and fingerprinted Notification Digest snapshots before rebuilding views.
  - `NothingAllAppsContainerView` & `NothingSearchAdapterProvider` & `DrawerHeader`: Cached ViewHolder references via `R.id.nt_view_holder_tag`, reused click listeners, enabled `isSpanGroupIndexCacheEnabled` with proper invalidation, replaced `distinctBy`/`groupBy` `Pair` churn with `ComponentKey`/`EnumMap`, and cached `DrawerHeader` visible count & recents signature.
  - `CommandEngine` & `NothingSearchAlgorithm`: Replaced thread-unsafe `DecimalFormat` with `ThreadLocal`, cached timezone lookup map & `SimpleDateFormat`, moved unit conversion tables to static maps, rewrote `scoreApp()` as zero-allocation character scanning, and used bounded top-K insertion in `NothingSearchAlgorithm`.
  - Cached reflection `Field` lookups in `NothingWidget` and `DrawerSearchBox`, and cached `SimpleDateFormat`/`Calendar` + view refs + timer signature checks in `DeskHudActivity` and `NothingDreamService`.
- **Release R8, Resource Shrinking & Packaging (`app/build.gradle`, `app/proguard-rules.pro`):**
  - Enabled `shrinkResources true` and obfuscated internal `com.nothinglauncher.**` helpers while preserving every `NothingWidget`, `NothingIconLogic`, `ResourceBasedOverride`, and `AppsSearchContainerLayout.mSearchBarController` contract; stripped release `Log.v`/`d`/`i` calls and excluded non-runtime `.proto`, `kotlin_builtins`, `DebugProbesKt.bin`, and `kotlin-tooling-metadata.json` metadata.
  - Measured release output vs v15: `app-release.apk` reduced `5,099,967` B -> `5,013,847` B (`-86,120` B), `com` DEX payload reduced `1,412,066` B -> `1,296,949` B (`-115,117` B), `classes.dex` reduced `3,476,516` B -> `3,438,332` B, raw `res/` reduced `1,594,107` B -> `1,529,928` B; all 13 custom widgets, `NothingIconLogic`, `mSearchBarController`, and 0 undesugared methods verified on shipped DEX via `dexdump`.

## [15] — Work-Tab Isolation, Live Cross-Surface Sync, World Clock & Studio UX Polish

### Added
- **Hardware Accent Swatch Strip (`AccentTheme.populateSwatchStrip`, `CustomizeSheet`, `SettingsUi.accentSwatchRow`):** Replaced the modal Accent dialog in Settings and added an inline 5-dot hardware phosphor swatch strip directly inside the Home long-press `CustomizeSheet`, supporting instant live Home preview and in-place Settings section-dot re-tinting without Activity recreate flicker.
- **Setup Hub Backup Symmetry & Backdrop (`LauncherEntryActivity`):** Added explicit **Import JSON** action alongside Export JSON, active-preset auto-detection on launch/resume, light/dark status-bar & navigation-bar contrast (`Theme.NothingLauncher.Entry`), and live `DotGridDrawable` backdrop.
- **Self-Explainable Vault & Structured Desk Note:** Added 1-tap **UNHIDE** pills and renamed-app custom labels to `HiddenVaultSheet`, structured Focus Note header cards (`deskNoteDot` + `EDIT` affordance) in `DeskHudActivity` phone/tablet layouts, crisp hairline borders on `bg_search_pill`, pill count badges + hairline rules on drawer category headers (`item_drawer_category`), pill A–Z tags in minimal list rows (`item_drawer_list_app`), cardinal bezel ticks on `GlyphMatrixCanvasView`, and refined peak/sparkline spacing across `MediaDotsView` and `WeatherDotsView`.
- **Tests:** Added [`V15FeaturesTest.kt`](app/src/test/java/com/nothinglauncher/V15FeaturesTest.kt) covering Work-profile vs Search adapter role detection, World Clock `DotMatrixClockView` timezone rendering, 3-digit >99m focus timers, `FocusTimerWidget` visibility pausing, `SmartFolderWidget` Vault filtering, `LauncherEntryActivity` day/night theme attributes, `CustomizeSheet` inline swatch bar, `NothingPrefs` v15 round-trip & soundscape bounds, and live `NothingLauncher` preference listeners.

### Changed
- Bumped `versionName` to `"15"` (`app/build.gradle`), export version/filename (`not-a-thing-v15-setup.json`), Setup Hub hero (`NOT A THING 15`), and Settings **Studio 15** live preview card.
- Consolidated shared visibility-gated widget hosting into `NothingWidget.LifecycleFrameLayout` (reused by `FocusTimerWidget` and `CompassStepWidget`).
- Updated `FocusSoundEngine` to modern API 26+ `AudioTrack.Builder()` with explicit `STATE_INITIALIZED` verification.

### Fixed
- **Work-Profile Drawer Misclassification & Hidden Filtering (`NothingAllAppsContainerView`, `NothingSearchAdapterProvider`, `DrawerHeader`):** Fixed `createAdapter()` mistakenly flagging the Work-profile adapter (`MAIN` vs `WORK` vs `SEARCH`) as a Search adapter, extended `applyHiddenFilter()` and `CATEGORIES` grouping to Work apps, resolved active `AllAppsRecyclerView.apps` safely during `onBindView`, applied `customAppLabels` and `IconPackManager` icons in Minimal List rows, and scoped `IconFactory` cache keys to icon style, pack, and night mode across Drawer List, Recent Apps (`DrawerHeader`), and `SmartFolderWidget`.
- **World Clock Display (`DotMatrixClockView`, `ClockWidget`, `DeskHudActivity`, `NothingDreamService`):** Added `timeZone` support to `DotMatrixClockView` so World Clock mode (`clockFaceMode == 3`) displays actual remote city time and date in the main dot-matrix clock with local time shown in the corner header pill; also gated `ClockWidget.word.glow` and `NothingDreamService` `glow` strictly on `VisualTier.effects().dotGlow`, and cached `ACTION_BATTERY_CHANGED` broadcasts in `NothingDreamService`.
- **Live Cross-Surface Sync & Background Power (`NothingLauncher`, `EssentialSpaceOverlay`, Widgets):** Added `OnSharedPreferenceChangeListener` in `NothingLauncher`, `WeatherWidget`, `MediaWidget`, `BatteryCalendarWidget`, `CompassStepWidget`, `FocusTimerWidget`, and `SmartFolderWidget` so toggling Focus Mode, Caffeine, Accent Palette, Hide Labels, Search Pill, Drawer Mode, or Widget Surfaces updates immediately across Home and Drawer; refreshed full overlay UI + `DotGridDrawable` on `-1` Dock Space Focus toggles and respected `Background.BLACK`; fixed `NothingSettingsActivity` icon-style highlight syncing via `NothingIconLogic.currentStyle()`; filtered Vault-hidden apps from `SmartFolderWidget` default resolution and picker; lifted the 99-minute display cap in `FocusTimerRepository`; added user toast feedback in `CommandEngine` (`focus`, `caffeine`) and `Intents.expandNotifications()`; and guarded `NothingLauncher.HomeGestureController` against active drags and double-tap event pass-through.
- **Kinetic Matrix Widget All 3 Modes (`KineticMatrixView`, `KineticMatrixWidget`, `VisualTier`):** Fixed all three modes (`GRAVITY`, `LIFE`, `SNAKE`) losing touches to the launcher widget host or freezing under Battery Saver / Reduced Motion: walked host ancestors with `requestDisallowInterceptTouchEvent(true)` + `cancelLongPress()`, declared `canScrollVertically`/`canScrollHorizontally` so downward drags aren't stolen as workspace gestures, decoupled active widget simulation from `ValueAnimator` motion scaling while keeping unit-test determinism via `VisualTier.isTestOverrideActive()`, added full-grid touch attract + drag impulse + rotation-remapped accelerometer + ambient sway in `GRAVITY`, added drag-painting + composition pause + stagnation/oscillator auto-reseed in `LIFE`, and added aspect-normalized edge taps + swipe steering + Game Over dim/accent visual & restart prompt in `SNAKE`.

## [14.1] — Security Hardening, Key Rotation, Brand Cleanup & Deep Bug Fixes

### Security
- **Release Signing Key Rotation & Secret Storage:** Generated a fresh 4096-bit RSA release keystore (`notathing-release`, SHA-256 `9F:53:42:FE:6B:3A:C5:96:18:D7:DF:13:AC:3E:14:1F:03:7A:24:DD:9F:5B:4A:F3:A2:97:19:A7:53:69:5B:97`) protected by a 256-bit SHA-256 hashed CSPRNG password, stored all signing credentials in GitHub Actions secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`), removed `keystore/stable-signing.p12` and all hardcoded keystore passwords from git (`app/build.gradle`, `.gitignore`), and updated [`.github/workflows/build.yml`](.github/workflows/build.yml) accordingly.
- **Hidden Vault Fail-Closed Authentication (`HiddenVaultSheet`, `VaultAuthActivity`):** Closed auth bypasses in `NothingSettingsActivity` and `DrawerHeader` (`showVaultDialog` restricted internal; every caller routes through `openWithAuth`), fixed Android 10 `BiometricPrompt` credential fallback, added dedicated non-exported `VaultAuthActivity` device-credential confirmation for Android 8–9 (`API 26–28`), and enforced fail-closed denial whenever `KeyguardManager.isDeviceSecure` is true.
- **ContentProvider & Input/IO Hardening:** Restricted `GridCustomizationsProvider` (`launcher3/AndroidManifest.xml`) with signature/system `READ_SETTINGS`/`WRITE_SETTINGS` permissions; bounded `SetupBackupManager.readBackupFromUri` (512 KB cap), `NothingPrefs.importFromJson` (length, regex, timezone ID, and range validation), and `WeatherRepository.getJson` (HTTPS-only, redirects disabled, 1 MB stream cap); synchronized `NotificationDigestRepository` and cleared cached entries/PendingIntents immediately when Notification Access is revoked.
- Added Android 14+ background activity start opt-in `ActivityOptions` when launching `NotificationDigestRepository` `PendingIntent`s from `EssentialSpaceOverlay`.

### Changed
- **Trademark Cleanup & Legal Attribution:** Scrubbed third-party brand/product wordmarks (`Signal Red`, `Pure Monochrome`, `StandBy Dot Clock`, `DOT AUDIO SYSTEM`, `MONOLITH AUDIO`, `Matrix Desk HUD`, `MATRIX`, `Dock Space`, `Focus Note`, `App Stack`, `Notification Digest`), replaced the adaptive launcher dot-`N` mark (`ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`) with a neutral 7×7 dot-matrix chamfered reticle + Signal Red core beacon, and added root [`NOTICE`](NOTICE) documenting AOSP Apache 2.0, Space Grotesk/Space Mono SIL OFL licenses, and non-affiliation.
- Bumped `versionName` to `"14.1"` (`app/build.gradle`, Setup Hub, Settings Studio hero).

### Fixed
- **Android 8–13 Runtime Compatibility (`#37`, Vivo Y15 / API 30 `LoaderTask` crash):** Enabled core library desugaring (`coreLibraryDesugaringEnabled = true` with `com.android.tools:desugar_jdk_libs:2.1.5`) across `:app` and `:launcher3`, rewriting Java 16 `Stream.toList()` and post-API-26 standard library calls in vendored AOSP Launcher3 (`LoaderTask.loadWorkspaceImpl()`, `LauncherModel`, `ReorderAlgorithm`, `WidgetsModel`, etc.) without editing `third_party/aosp/**`, and extended `tools/verify_release_reflection.py` to fail any release build with undesugared `Stream.toList()` / `List|Set|Map.of|copyOf` references.
- **Audio & Hardware Stability:** Guarded `FocusSoundEngine` with atomic session tokens and error-checked `AudioTrack.write` loops to stop dual-thread leaks on rapid OFF/ON toggles; padded and reused a single serialized static `AudioTrack` on a background thread in `TactileAudio` to eliminate per-tap allocations and main-thread HAL failures; bound `HardwareControlRepository.registerTorchCallback` to the main `Looper` `Handler` to prevent cross-thread View exceptions.
- **HUD, StandBy Dream & `-1` Dock Space Lifecycle:** Eliminated per-second/500ms Binder IPC and JSON parsing in `DeskHudActivity` and `NothingDreamService` by caching minute-scoped calendar/alarm lookups and listening to battery/alarm broadcasts; replaced brittle `sec == 0` burn-in shift triggers with `lastShiftMinute`; wired `MediaSessionRepository.start()` in `NothingDreamService`; properly detached all `EssentialSpaceOverlay` listeners in `applyProgress(0f)` and `onActivityPaused()` (restoring zero-idle `WeatherRepository` privacy); replaced root `NestedScrollView` touch listener with a container `onInterceptTouchEvent` swipe host so horizontal closing drags work anywhere over cards; mounted `EssentialSpaceOverlay` inside `LauncherRootView` (`Insettable` sibling above `DragLayer`) so AOSP `DragLayer.onOverlayScrollChanged` no longer fades/slides the `-1` overlay off-screen to a black backdrop; added user feedback & App Info fallback on permanent `RECORD_AUDIO` denial.
- **Weather, Search & Smart Drawer:** Fixed `WeatherRepository.runFetch` queue lock race and aligned 12-hour forecast slice index to Open-Meteo `current.time` location timezone; added monotonic query sequence tokens in `NothingSearchAlgorithm`; restricted search highlight tracking in `NothingSearchAdapterProvider` to active search queries, fixed `VIEW_TYPE_LIST_APP` Enter-key launches, and eliminated `componentName!!` NPE; cached `AppCategorizer.bucketFor` per package; fixed Categories mode column math (`numShownAllAppsColumns`), `fastScrollerSections` rebuilding, and `isSpanIndexCacheEnabled = true` in `NothingAllAppsContainerView`.
- **Widgets, Views & Light-Mode Contrast:** Prevented 180-degree self-collision on rapid double-taps in `KineticMatrixView` (`committedSnakeDirX/Y`); gated `BatteryDotsView`, `MediaDotsView`, and `DotGridDrawable` ripples on `VisualTier.motionAllowed`, fixed `DotRingGaugeView` `litCount == 0` lead dot, switched paint SP text sizing to `TypedValue.applyDimension`, and cleared `DotGridDrawable` ripple shaders/animators when invisible; added `cacheKey`, icon pack resolution, in-place picker row updates, and live preference listeners in `SmartFolderWidget`, `HabitMatrixWidget`, `VitalsWidget`, `DeskBriefingWidget`, and `KineticMatrixWidget`; fixed Light-mode active tile contrast via `bg_tile_active` (`QuickTogglesWidget`, `DeskHudActivity`, `EssentialSpaceOverlay`, `LauncherEntryActivity`, `SmartFolderWidget`); fixed `sheet_vault.xml` scroll height cap, `dialog_edit_note.xml` multiline sizing/`Sheets` input type, and dead `"NEAR"` branch in `WordClockFormatter`.
- Added [`V14_1AuditFixesTest.kt`](app/src/test/java/com/nothinglauncher/V14_1AuditFixesTest.kt) covering Vault fail-closed auth policies, Snake committed-direction anti-reversal, SoundEngine rapid toggle safety, Prefs import bounds/timezone validation, Weather location-timezone alignment, Search cancellation/sequence filtering, and WordClock output.

## [14] — Phone-First Interactive Experience, Glyph Desk HUD & Big-Screen Studio

### Added
- **Archive:** Preserved v13 documentation under `docs/archive/` (`ARCHITECTURE.v13.md`, `FEATURES.v13.md`, `RELEASE_V13.md`).
- **Android System StandBy Screensaver (`NothingDreamService`):** Registered interactive system `DreamService` (`res/xml/nothing_dream_info.xml`, `dream_nothing_standby.xml`, accessible via **StandBy screensaver** in Settings) turning any charging/docked phone into an OLED clock console with pixel shift, battery %, weather/media/alarm line, Notification Digest status, and 1-tap `DeskHudActivity` launch.
- **13th Widget — Kinetic Matrix (`KineticMatrixWidget`, `KineticMatrixView`):** Interactive `16×10` LED dot playground switching between accelerometer tilt-driven `GRAVITY` beads + touch attract, tap-to-seed Conway's `LIFE`, and playable home-screen Dot `SNAKE` (`kineticMode` pref + per-widget surface theming).
- **On-Device Essential Notification Digest (`NotificationDigestRepository`):** Offline priority-grouped active notification summary inside `-1` Essential Space (`spaceDigestCard`) and `NothingDreamService`, powered by AOSP `NotificationListener` with automatic Focus Shield suppression (`FOCUS SHIELD ACTIVE`).
- **Procedural Focus Soundscapes (`FocusSoundEngine`):** Zero-asset stereo PCM `AudioTrack` synthesis (`BROWN`, `RAIN`, `40HZ`) toggleable from `-1` Essential Space (`spaceSoundscapeBtn`) and Desk HUD (`deskSoundscapeBtn`), synced with `GlyphMatrixCanvasView.isAudioActive` and persisted via `soundscapePreset`.
- **Big-Screen Telemetry Studio (`BigScreenUi` & `w600dp`+ layouts):** Responsive breakpoints (`w600dp` / `w680dp` / `w900dp`), dual-column `placeDefaultTiles()` Bento layout on 6+ column workspaces, centered tablet bottom sheets (`540dp` max width), 2-column presets grid in `LauncherEntryActivity` with active preset highlight, multi-column drawer list (`1/2/3` columns), and 2-column Command Search cards.
- **4-Stage Glyph Desk HUD (`DeskHudActivity`, `GlyphMatrixCanvasView`):** Added a 4-stage mode switcher bar (**COCKPIT**, **CLOCK** StandBy theatre, **FOCUS** Zen Pomodoro studio, and **GLYPH** procedural LED matrix canvas reactive to audio/soundscape playback, Focus Timer progress, and touch ripples), 1-tap Habit check-in toggle (`deskHabitBtn`), hardware keyboard navigation (`1–4`, `Tab`/`M`, `C`, `F`, `H`, `Space`), persisted `deskMode` preference, and `@style/Theme.NothingLauncher.Desk`.
- **12th Widget — Desk Briefing (`DeskBriefingWidget`):** Greeting, cached weather, calendar/alarm briefing, interactive Focus Timer pill, and 1-tap **ENTER DESK HUD** button (`widget_desk_briefing.xml`).
- **2-Column Bento Essential Space & Settings Studio:** Extended `layout-w600dp/essential_space_overlay.xml` + `EssentialSpaceOverlay` with live Habit Matrix check-in pegboard (`HabitPegboardView`) and Now Playing Media Deck (`MediaArtDotsView`, `MediaDotsView`, transport buttons); upgraded `NothingSettingsActivity` Studio 14 card with a live preview `DotMatrixClockView` + `SecondsTickerView` and **Widget surfaces** action.
- **Per-Widget Surfaces & Essential Stack Max Icon:** Added per-widget surface finishes (**Default**, **Obsidian Dark**, **Bone Paper**, **Frosted Glass** via `AccentTheme.WidgetSurface` + `CustomizeSheet.showWidgetSurfacesSheet` + `.nothing.json` export/import) and 1-app Max Icon mode inside `SmartFolderWidget`.
- **Smart Drawer Categories & Hardware Keyboard Search:** Added **GRID ↔ LIST ↔ CATS** (`DrawerLayoutMode.CATEGORIES` + `AppCategorizer`), `/` / Search key shortcut on Home (`NothingLauncher.onKeyUp`), plus CommandEngine hex color inspection and quick utilities (`uuid`, `dice`, `coin`, palette).
- **Weather Telemetry & Tests:** Added hourly rain probability baseline and wind speed (`KM/H`) to `WeatherDotsView`, added [`V14FeaturesTest.kt`](app/src/test/java/com/nothinglauncher/V14FeaturesTest.kt) (covering Desk HUD, `GlyphMatrixCanvasView`, `WeatherDotsView`, Big-Screen Studio, API 30 Issue `#32` `Scrim` fix, `KineticMatrixView`/`KineticMatrixWidget`, `NotificationDigestRepository`, `FocusSoundEngine`, and `NothingDreamService`) and [`BigScreenScreensTest.kt`](app/src/test/java/com/nothinglauncher/BigScreenScreensTest.kt) (`PIXEL_C` tablet Paparazzi targets), expanded [`ScreensTest.kt`](app/src/test/java/com/nothinglauncher/ScreensTest.kt) with `widgetsShowcase` (including `KineticMatrixWidget`) and `deskHudGlyph`, and updated `V13FeaturesTest` to cover all 13 custom widgets.

### Changed
- Bumped `versionName` to `"14"` in `app/build.gradle`.
- Updated `docs/ARCHITECTURE.md`, `docs/FEATURES.md`, `docs/SCREENSHOTS.md`, and `README.md` for v14.

### Fixed
- **Android 8–11 (`API 26–30`) Boot Crash (Issue #32):** Added concrete base `values/nt_colors.xml` and `color/*.xml` overrides for `wallpaper_popup_scrim`, `work_turn_on_stroke`, `arrow_tip_view_bg`, `arrow_tip_view_content`, `button_bg`, and `button_text` (via `tools/gen_nothing_theme.py`), preventing `Resources$NotFoundException: type #0x2 is not valid` in `Scrim` on Android 11 devices such as Vivo Y15 without touching vendored AOSP sources.
- **Paparazzi & Layout Fixes:** Fixed dim/unlit clock colons (`previewTime`/`previewSecond` in `ScreensTest`), bottom clipping of `clockDate` on 2-row clock tiles (`widget_clock.xml` `match_parent`), orphan calendar alarm icon visibility (`widget_calendar.xml`), light-mode Battery border visibility (`bg_tile_light.xml`) and right-edge alignment across Battery/Calendar tiles (`FrameLayout` replacing `SquareFrameLayout` in `widget_battery.xml` and `widget_calendar.xml`), Settings vertical rhythm so `Visual quality` fits unclipped (`activity_settings.xml`, `item_setting_section.xml`, `item_setting_row.xml`), and Drawer header sizing/spacing (`nt_drawer_header.xml`, `ScreensTest.drawer()`).
- Fixed a boot crash (`IllegalArgumentException: The observer is null`) in `NothingAllAppsContainerView` by attaching `categorySyncObserver` after `super.<init>()` in `onFinishInflate()` / `rebindAdapters()`.
- Fixed `DeskHudActivity` theme inheritance (`Theme.NothingLauncher.Desk` extending AppCompat-based `Theme.NothingLauncher`) and `LauncherEntryActivity` subtitle color token (`R.color.ink_secondary`).

## [13] — Essential Space, Command Search, and 11 interactive elements

### Added
- **Archive:** Preserved historical v12 documentation under `docs/archive/` (`ARCHITECTURE.v12.md`, `FEATURES.v12.md`, `RELEASE_V12.md`).
- **In-process Essential Space (`-1` overlay):** Swipe right from Home page 0 to reveal [`EssentialSpaceOverlay`](app/src/main/java/com/nothinglauncher/space/EssentialSpaceOverlay.kt) via Launcher3's `LauncherOverlayManager`: time-aware greeting, Weather + Calendar/Alarm briefing, Hardware Switchboard (Torch, Focus Mode, Caffeine, Ringer), Scratchpad Note, instant Voice Memos, Focus Timer/Stopwatch ticker, and Storage/RAM/Screen-Time Vitals.
- **7 new in-process home elements (11 total):** `QuickTogglesWidget`, `FocusTimerWidget`, `EssentialNoteWidget`, `HabitMatrixWidget`, `VitalsWidget`, `CompassStepWidget`, and `SmartFolderWidget`, plus Android S+ widget picker previews (`preview_widget_*.xml`) and descriptions across all 11 widgets.
- **Offline Command Search & Smart Drawer:** `CommandEngine` (math & percentage calculator, unit/temperature converter, `time <city>` lookup, device toggles, optional contacts search, web search, acronym & 1-typo fuzzy app matcher), `NothingSearchAlgorithm` + `NothingSearchAdapterProvider` Command Cards, `DrawerHeader` with Grid/List switcher and recent apps strip, and biometric `HiddenVaultSheet`.
- **Personalization, Physics & Gestures:** 5 hardware accent palettes (`RED`, `AMBER`, `PHOSPHOR`, `COBALT`, `MONO` via `AccentTheme`), `IconStyle.DOTS` (13×13 dot-glyph icon rendering) + third-party ADW/Nova `IconPackManager`, per-app custom labels (`NothingShortcuts.RENAME_APP`) & Hide in Vault shortcut (`NothingShortcuts.HIDE_IN_VAULT`), SpringAnimation tile physics (`Tactile`) with optional synthesized click audio (`TactileAudio`), interactive backdrop parallax/tap-ripples/night-dimming (`DotGridDrawable`), label-free home & hotseat pill toggles, and Swipe-Down / Double-Tap / Pinch home gestures with optional `NothingLockAccessibilityService`.
- **Setup Hub, Curated Presets & Backup:** `LauncherEntryActivity` onboarding & permission hub, 4 one-tap starter presets (`Telemetry v13`, `Monolith Minimal`, `Command & Focus`, `Studio Media`), and SAF `.nothing.json` export/import in `SetupBackupManager`.
- **Tests:** Added [`V13FeaturesTest.kt`](app/src/test/java/com/nothinglauncher/V13FeaturesTest.kt) covering offline math/conversions/fuzzy search, Word Clock, Bayer halftone, Focus Timer/Stopwatch, AccentTheme, JSON export/import & presets, `DOTS` icon rendering, all 11 widget providers, and Settings/Entry inflation.

### Changed
- **Upgraded Core 4 widgets:** `ClockWidget` now cycles Digital, Analog Dot-Matrix, Editorial Word Clock, and World Clock faces without clipping the date on 2-row grids; `BatteryCalendarWidget` gains charging-bolt indicator and hides empty alarm rows cleanly; `WeatherWidget` gains compact vs wide 12-hour temperature sparkline + wind speed; `MediaWidget` adds background 10×10 Bayer 4×4 halftone album art (`MediaArtHalftone`) and active audio route indicator.
- **Settings & Customize Sheet:** Expanded `NothingSettingsActivity` with Personality & Physics, Gestures & Home Cleanliness, Essential Space & Privacy, and Presets/Backup sections; added quick chips for Essential Space, Presets, and Hidden Vault to `CustomizeSheet`.
- Bumped `versionName` to `"13"` in `app/build.gradle`.

### Fixed
- Fixed light-mode contrast on active dialog/media pills (`dialog_edit_note.xml`, `dialog_confirm.xml`, `widget_media_player.xml`) using `@color/pill_active_text`.
- Fixed clock date clipping on 2-row cells in `widget_clock.xml` and orphan alarm icon visibility in `widget_calendar.xml`.

## [12] — Fits the phone, and a bit more character

### Added
- **Visual quality that fits the phone** (Settings → Look → Visual quality: Auto / Lite / Full).
  Auto reads RAM and CPU cores and shows what it found. 3–4 GB phones (including 3.5 GB) get
  **Lite**: flat dot grid, no LED glow, no power-on sweep or entrance motion, a calmer media
  visualiser, and the charging pulse redrawn ~8×/s instead of every frame. 6 GB phones get
  **Balanced**. 8 GB+ phones get **Full**: everything, plus the backdrop light pool and blurred
  sheets.
- **Backdrop light pool** (Full): dots near the top of the screen glow brighter and slightly
  larger, like an LED panel lit from above.
- **LED clock power-on**: back on home after 5+ minutes, a red scan line sweeps across the clock
  and lights the digits behind it (Balanced and Full).
- **Tactile tiles**: element tiles and Customize sheet tiles sink a little under the finger and
  spring back with a light haptic tick. Settings toggles and choices tick too.
- **Frosted sheets** on Android 12+ (Full, where the device allows blur).
- Settings cards slide in one after another (Balanced and Full).
- New screenshots: `homeLite` (Lite tier) and `clockPowerOn` (the sweep, frozen mid-way).

### Changed
- Element tiles have a faint top-down sheen instead of a flat fill.
- Decorative motion stops while Battery Saver is on or system animations are off, on every
  tier.
- **Android 17 memory limits.** Android 17 caps each app's memory relative to the device's RAM.
  When home goes to the background the Not a Thing layer drops its own caches and closes the
  widget picker (its previews are the largest bitmaps held for it). On Android 11+, if the system
  ever closed home to free memory (the low-memory killer or Android 17's `MemoryLimiter`), Auto
  quality switches to Lite and says so in settings.
- Version is now **12**, published as release **v12** (the workflow names releases after
  `versionName`).

### Fixed
- Possible crash registering the Not a Thing widgets on Android 8.0/8.1: a widget field that
  only exists from Android 9 was written unconditionally (found by lint's API check).
- Settings switches were invisible when on in light mode (a white pill on a white card, so only
  a lone dot showed). The "on" track now uses the active colour: black on light, white on dark.
- The weather tile's crescent moon is now a real cut-out shape instead of a disc painted in the
  tile colour, so it stays correct on the new tile sheen.

### Docs
- `RELEASING.md`: removed the "release titled v10.0" limitation (the live workflow already names
  releases after `versionName`) and documented how release names are derived.

Not measured: memory and frame timings on real 3.5 GB / 8 GB devices, and behaviour on an
Android 17 device or emulator. None were available. Unit, Robolectric and screenshot tests run
on the JVM.

## [11.2] — Android 16 target, reliability fixes and a cleaned-up repo

### Changed
- **Targets Android 16 (API 36)**, the level Google Play requires since August 2026. Builds and
  passes every test at it, and was checked against each Android 16 targeting change: predictive back
  (Launcher3 already handles it), mandatory edge-to-edge, and large screens ignoring orientation
  locks (home was already resizeable). `compileSdk` stays 35 because the vendored Launcher3
  resources use `android:attr/materialColor*`, which the API 36 SDK no longer has.
- The settings screen uses predictive back on Android 14 and 15 too, matching Android 16+.
- Smoother, cheaper home screen, with pixel-identical output (screenshot goldens unchanged):
  - the media tile no longer redraws on every playback-position update (about once a second);
  - the battery ring redraws only when the level or charging state changes;
  - the LED clock rebuilds its digits once a minute instead of every second;
  - icon rendering reads pixels in one bulk call instead of 2,304 calls per app.
  Not profiled on a device.
- Measured the 11.1 R8 keep-rule change on a real release build: APK 5,205,285 → 4,790,157 bytes
  (−8.0%), `classes.dex` −29.4%, dex methods 31,835 → 25,361.

### Fixed
- **No more network traffic without a weather widget.** Home looked up the device's location from
  its public IP and fetched a forecast about every half hour, even with no weather widget on
  any screen. It now only does so while one is shown.
- **Weather no longer falls back to London** when ipapi.co rate-limits the network (HTTP 429,
  common on mobile carriers). ipwho.is is tried first.
- **Release builds of 11.1 had no Not a Thing widgets** (clock, battery & date, weather, media
  were missing from the picker and from first run). R8 stripped them because Launcher3 loads
  them by name; a precise keep rule fixes it, and every release build is now checked for it
  automatically (see Added).
- The default clock and battery & date tiles could be skipped for good on first run if Launcher3
  hadn't registered the widgets yet. Placement is now retried.
- The battery & date tile could show yesterday's date after the clock was set back or the phone
  came out of deep sleep. It now refreshes whenever home is shown.
- Settings rows ran under the navigation bar and camera cutout in landscape.
- The Customize sheet could leak its window when the system switched dark mode (or font/display
  size) while it was open.
- A possible crash from the weather thread and the UI writing the same settings map at once.
- The weather service User-Agent reported version 10.1 instead of the real version.

### Removed
- **`ci/`** (staged workflow copies, a workflow-sync bot and a script to re-sign old releases).
  None of it ever ran, and it hid fixes where nobody would apply them. How releases actually
  work, plus the one optional manual workflow tweak, is now in
  [`docs/RELEASING.md`](docs/RELEASING.md).
- 9,073 Gradle build-output files that were committed by mistake, so a local build no longer
  dirties the working tree.

### Added
- `docs/RELEASING.md`: how every push to `main` becomes an installable update, signing, and
  publishing a numbered version.
- `tools/verify_release_reflection.py`, run at the end of every `assembleRelease` (so CI runs
  it too). It inspects the real release dex and fails the build if R8 removed anything Launcher3
  creates by name: widgets, `*_class` overrides and `Overrides.getObject` fallbacks, 18 targets.

### Security
- `protobuf-javalite`/`protoc` 3.25.3 → 3.25.9, fixing
  [CVE-2024-7254](https://github.com/advisories/GHSA-735f-pc8j-v9w8) (CVSS 7.5): a crafted
  message could crash the launcher with a `StackOverflowError`. Stayed on 3.25.x to keep the
  generated code compatible with Launcher3's protos.

## [11.1] — Release build optimization and Android 15 compatibility

### Changed
- `targetSdk` raised from 34 to 35 (Android 15), matching `compileSdk`, so the shipped APK is no
  longer two platform releases behind the compiler and stays eligible for Play Store submission.
  Android 15 enforces edge-to-edge for apps targeting SDK 35, which Launcher3's own theme already
  draws for (translucent status/nav bars — see `third_party/aosp/launcher3/res/values/styles.xml`),
  so this is a compatibility bump rather than a layout change.
- Fixed a ProGuard/R8 rule bug in `app/proguard-rules.pro` that was silently defeating most of
  R8's work on release builds: it added a second, unscoped `-keep class com.nothinglauncher.** {
  *; }` / `-keep class com.android.launcher3.** { *; }` on top of Launcher3's own
  `-keep,allowshrinking,allowoptimization` rule (applied automatically via the `:launcher3`
  module's `consumerProguardFiles`). A plain `-keep` is strictly stronger and wins, so neither
  package was actually being shrunk, inlined, or dead-code-eliminated — R8 was only trimming
  third-party libraries. The app's own rules now use the same `allowshrinking,allowoptimization`
  scoping AOSP uses for Launcher3: class *names* stay intact (`ResourceBasedOverride`/
  `CustomWidgetPlugin` still resolve implementations by name from resources/XML), but R8 can once
  again strip unused methods/fields and optimize the method bodies of effectively the whole app,
  which is where almost all code lives — smaller, faster-loading release APK for the same
  behaviour.
- `android.enableR8.fullMode=true` set explicitly in `gradle.properties` (already AGP 8's default,
  but now can't silently regress to the slower/less-aggressive legacy R8 mode on a future AGP
  bump) and `org.gradle.parallel`/`org.gradle.caching` enabled for faster local/CI builds
  (build-time only — no effect on the shipped APK).
- Resource shrinking (`shrinkResources`) was evaluated and deliberately left off: Launcher3
  resolves several resources dynamically via `Resources.getIdentifier` (grid-size preview
  thumbnails in `GridCustomizationsProvider`, OEM `Partner` overlays, `DeviceProfile` grid
  option names), which the default resource shrinker cannot see are in use and would strip,
  breaking the grid picker. Enabling it needs explicit `tools:keep` rules first; left as a
  follow-up rather than shipping something unverified.

### Fixed
- Kotlin compile error in `NothingPrefs.addListener`/`removeListener` (missing `?.` safe calls
  on a nullable `SharedPreferences`) that broke `compileDebugKotlin` and failed every CI run at
  the Paparazzi screenshot test step.
- Widget picker missing on devices with no other widget provider installed — ship a real
  "Date & time" `AppWidgetProvider` so Launcher3's `CustomWidgetManager` always has at least one
  provider to register the in-process Not a Thing widgets against.
- Weather never loading (missing `INTERNET`/`ACCESS_NETWORK_STATE` permissions).
- Home tiles inheriting another app's configure activity and size limits from the cloned
  provider info, which could crash adding a widget ("Bad widget id").
- First run leaving a resize frame open around the default tiles.
- Media and weather widgets not refreshing on returning to home; the seconds-ticker setting only
  applying after a restart.
- Battery/date tiles cropping on wide cells.
- Rotation switch defaulting to the tablet value on phone grids; grid row showing on tablet
  grids.
- "Advanced home settings" row added so Launcher3's stock settings (e.g. notification dots) are
  reachable again now that Not a Thing's settings screen claims `APPLICATION_PREFERENCES`.
- Home rebuilding (closing the drawer/folders) on a new wallpaper even when the wallpaper isn't
  the active backdrop.

### Added
- End-to-end Robolectric test (`LauncherRuntimeTest`) that boots the real launcher on the JVM
  and drives home, first-run tiles, drawer + search, the customize sheet, the widget picker,
  pause/resume and both settings screens, printing the full logcat — this is what surfaced most
  of the fixes above.

## [11.0] — Rebuild on stock AOSP Launcher3

This release replaced the entire pre-11.0 codebase (a from-scratch Kotlin home screen) with
**stock AOSP Launcher3** (`android-14.0.0_r75`, vendored under `third_party/aosp/`) plus a thin
Not a Thing design layer on top. Home screen, app drawer, folders, widgets, drag & drop, app
shortcuts and settings now all behave exactly like stock Android, because they *are* stock
Android.

### Changed
- Fixed monochrome + Nothing-red palette replacing Launcher3's Material-You colours, generated
  by `tools/gen_nothing_theme.py` into `app/src/main/res-launcher3-theme`.
- App icons rendered as the Not a Thing glyph-in-a-disc (mono) or artwork-in-a-circle (colour)
  via `NothingIconLogic`, hooking into Launcher3's own icon pipeline.
- Not a Thing's Customize sheet (Wallpaper & style, Widgets, Settings) replaces Launcher3's
  stock long-press menu.
- The v10-era bespoke dot-matrix elements (LED clock, battery/calendar, weather, media) were
  rebuilt as Launcher3 in-process `CustomWidgetPlugin` widgets, so they live in the stock widget
  picker and are added/moved/resized like any Android widget, instead of being hardcoded into a
  custom grid.
- Not a Thing's own settings screen now reads/writes grid size, icon style and rotation directly
  through Launcher3's `LauncherPrefs`/`InvariantDeviceProfile`, instead of a separate schema.

### Removed
- The bespoke `CellLayout` grid, drag controller, folders/Max-Icons implementation, and the
  larger elements suite (compass, pedometer, stopwatch, screen time, storage/RAM, world clock,
  voice recorder, quick toggles, quick note) from the pre-11.0 codebase. None of this is
  reimplemented on top of Launcher3 yet; see the [pre-11.0 docs](docs/archive/) for what existed.

Historical detail: see `docs/archive/` for the pre-11.0 architecture/feature docs, and the
pre-11.0 release notes below.

## [10.1] — App icon, painless updates & polish

- Added a real app icon (`LauncherEntryActivity` trampoline) with first-run welcome tour and
  "Set as default home" shortcuts — previously the launcher had no icon and no way to enable it
  short of the system's Default apps settings.
- Every build signed with one permanent key and a monotonically increasing `versionCode`, so
  releases install over the previous version instead of requiring an uninstall.
- Shortcut icons switched to a white-on-black glyph so they stay legible on light shortcut
  menus.

## [10.0] — General availability: zero-idle battery & cold start

- `CompassRepository`/`StepRepository` fully unregister sensor listeners on `onStop`, screen
  off, or losing foreground — previously they stayed registered indefinitely.
- Compass polling additionally throttled while the drawer is being dragged and on workspace
  pages without a compass element.
- All animated dot views gated behind `onVisibilityAggregated`/`isShown`, stopping tickers and
  animators while off-screen.
- In-memory `LruCache<String, Bitmap>(300)` for rendered icons, removing synchronous canvas work
  from cold start and drawer scrolling.
- Added `border_subtle`/`surface_dark` night-mode tokens; data schema bumped to `v3` with a
  non-destructive migration.

## [9.0] — Native launcher rebuild & unified grid

- Replaced segmented view stacks with a single `CellLayout` 2D grid that freely interleaves
  apps, folders, Max Icons, bespoke elements and third-party widgets.
- Added Nothing-style folders (1×1 disc with mini previews, 2×2 enlarged tiles) and 2×2 "Max
  Icon" tiles.
- Fixed first-run onboarding (previously present but never actually launched).
- Media controller became a real `MediaSessionManager`-backed player; double-tap-to-sleep now
  uses the accessibility global action instead of a no-op broadcast.
- Added folder rename, element/widget reordering, granular permission rows (notification access,
  usage access, accessibility, motion, microphone), and per-user weather/step personalisation.
- `versionCode 11`; stable project signing key introduced so future versions install in place.

## Earlier releases (v2–v8)

Incremental features and fixes on the original from-scratch Kotlin launcher: multi-page
workspace, dot page indicator, LED clock, battery ring, weather tile, quick toggles, pedometer,
compass, screen time, storage meter, world clock, OLED dark/light themes, monochrome icon
engine, double-tap-to-sleep, drawer search ranking and the A–Z scrubber. See `docs/archive/` and
the `v2`…`v8` git tags for details; none of this code survived the v11.0 Launcher3 rebuild.
