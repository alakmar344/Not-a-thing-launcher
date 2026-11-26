> **Historical (v13)** — archived on transition to **v14** (Big-Screen Studio, 4-stage Glyph Desk HUD, Widget Surfaces, Smart Folder Max Icon, and Drawer Categories). Kept for historical reference; see [`../ARCHITECTURE.md`](../ARCHITECTURE.md) for current architecture.

---

# Architecture (v13)

Not a Thing is a **design, telemetry, and productivity layer on top of stock AOSP Launcher3**, not a launcher written from scratch. Upstream Launcher3 (vendored unmodified under `third_party/aosp/launcher3`) owns the workspace database, drag & drop, folders, widget hosting, and loader lifecycle; product code lives under `app/src/main/java/com/nothinglauncher/` and resource overrides under `app/src/main/res/`.

## 1. How the layer attaches to Launcher3

| Extension point | What Not a Thing v13 did with it |
|---|---|
| `Launcher` activity subclass | [`NothingLauncher`](../../app/src/main/java/com/nothinglauncher/home/NothingLauncher.kt) wired `EssentialSpaceOverlay`, backdrop & parallax/ripples, home gestures (`createTouchControllers()`), long-press shortcuts (`getSupportedShortcuts()`), recent launch tracking (`startActivitySafely()`), label-free home visibility, `CustomizeSheet`, and first-run tiles |
| `LauncherOverlayManager` + `LauncherOverlayTouchProxy` | [`EssentialSpaceOverlay`](../../app/src/main/java/com/nothinglauncher/space/EssentialSpaceOverlay.kt) implemented an in-process `-1` overlay |
| `AllAppsContainerView` / Search layout overrides | `res/layout/all_apps.xml` swapped in `NothingAllAppsContainerView`; `NothingSearchAlgorithm` + `NothingSearchAdapterProvider` handled inline Command Cards and Grid/List switching |
| `R.string.launcher_activity_logic_class` | `NothingIconLogic` intercepted icon rendering and custom labels |
| `SettingsActivity` replacement | `NothingSettingsActivity` served `APPLICATION_PREFERENCES` |
| `CustomWidgetPlugin` / `R.array.custom_widget_providers` | Registered 11 `NothingWidget` elements |

## 2. Dot-Matrix Widgets (11 Elements in v13)

0. `ClockWidget`
1. `BatteryCalendarWidget`
2. `WeatherWidget`
3. `MediaWidget`
4. `QuickTogglesWidget`
5. `FocusTimerWidget`
6. `EssentialNoteWidget`
7. `HabitMatrixWidget`
8. `VitalsWidget`
9. `CompassStepWidget`
10. `SmartFolderWidget`

## 3. Search, Drawer & Hidden Vault (v13)

- `CommandEngine`: offline math/conversions/timezone/contacts/fuzzy app search.
- `NothingAllAppsContainerView`: Grid <-> Minimal List drawer switcher + `HiddenVaultSheet`.
- `SetupBackupManager`: 4 starter presets + `.nothing.json` SAF export/import.
