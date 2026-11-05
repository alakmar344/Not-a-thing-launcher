# Changelog

All notable changes to Not a Thing Launcher. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Dates are release dates, not commit
dates.

## [Unreleased]

### Added
- `tools/verify_release_reflection.py`, run in CI after `assembleRelease`: parses the real release
  dex and the merged resources and fails the build if anything Launcher3 instantiates by name
  (every `custom_widget_providers` entry, every non-empty `*_class` `ResourceBasedOverride`
  string, and the no-arg constructor of every `Overrides.getObject(Base.class, …)` fallback,
  whose failure is an uncaught crash) is missing or lost the constructor/supertype it needs.
  18 targets today. Verified it fails on the broken 11.1 output and passes on the fixed one.

### Changed
- Measured the 11.1 R8 keep-rule change for the first time (same inputs, real `assembleRelease`):
  APK 5,205,285 → 4,790,157 bytes (−8.0%); `classes.dex` 4,480,260 → 3,164,768 bytes (−29.4%);
  dex methods 31,835 → 25,361, fields 33,579 → 11,749, strings 35,570 → 20,274. Startup/RAM
  effects were not measured (no device available).

### Security
- `protobuf-javalite`/`protoc` bumped from 3.25.3 to 3.25.9 (`launcher3/build.gradle`). 3.25.3 is
  affected by [CVE-2024-7254](https://github.com/advisories/GHSA-735f-pc8j-v9w8) (CVSS 7.5):
  unbounded recursion while parsing unknown fields lets a maliciously crafted protobuf message
  crash the process with a `StackOverflowError`. This library is linked into the shipped app (it
  decodes Launcher3's own `LauncherAtom`/`LauncherDumpProto` logging/dump messages), so it's part
  of the installed APK's real attack surface, unlike the project's test-only dependencies. Stayed
  on the 3.25.x patch line rather than jumping to the 4.x major to keep the generated-code ABI
  unchanged for Launcher3's checked-in proto sources.

### Fixed
- **Release builds of 11.1 shipped without any Not a Thing widgets** (LED clock, battery & date,
  weather, media — gone from the widget picker, and no clock/battery tiles on first run). 11.1's
  narrower R8 keep rules let R8 delete `WeatherWidget`/`MediaWidget`/`NothingWidget` outright and
  strip the `(Context)` constructor and `CustomWidgetPlugin` supertype from `ClockWidget`/
  `BatteryCalendarWidget`, because Launcher3 only references them by name from
  `R.array.custom_widget_providers` (`Class.forName(...).getDeclaredConstructor(Context.class)`),
  which R8 can't see. Debug builds aren't minified, so every test passed. Fixed with a precise
  keep rule for `CustomWidgetPlugin` implementations in `launcher3/consumer-rules.pro`; found by
  measuring the actual release dex rather than trusting the rule change.
- `CustomizeSheet`'s dialogs (the long-press "Customize" sheet and its "Wallpaper & style"
  sub-sheet) could outlive `NothingLauncher` and leak their window ("WindowLeaked") if the
  Activity was destroyed and recreated while one was open — e.g. the system switching to dark
  mode on its scheduled time, or a font-scale/display-size change, neither of which is in
  `NothingLauncher`'s `android:configChanges` list, so Android tears the Activity down and
  rebuilds it without going through any of the sheet's own dismiss paths. `CustomizeSheet` now
  tracks whichever of its dialogs is showing and `NothingLauncher.onDestroy()` dismisses it.

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
