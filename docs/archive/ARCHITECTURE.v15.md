# Architecture (v15 Snapshot)

> **Historical snapshot — superseded by [`../ARCHITECTURE.md`](../ARCHITECTURE.md) in v15.5.**  
> Preserved so architectural changes between v15 and v15.5 remain traceable.

Not a Thing v15 layered on top of unmodified AOSP Launcher3 (`third_party/aosp/launcher3`) with `com.nothinglauncher.*` in `app/src/main/java/com/nothinglauncher/`:

- **Entry & Extensions:** `NothingLauncher` (`Launcher`), `EssentialSpaceOverlay` (`LauncherOverlayManager`), `NothingAllAppsContainerView`, `DrawerSearchBox` (`NothingSearchAlgorithm`, `NothingSearchAdapterProvider`), `NothingIconLogic` (`ResourceBasedOverride`), `NothingSettingsActivity` (`tools:node="replace"`), `app/src/main/res-launcher3-theme`, and 13 `NothingWidget` `CustomWidgetPlugin` providers (`DateTimeWidgetProvider` + `R.array.custom_widget_providers`).
- **Consoles & Repositories:** `NothingDreamService` (`DreamService`), `DeskHudActivity` + `GlyphMatrixCanvasView`, `BigScreenUi`, `NothingPrefs`, `SetupBackupManager`, `WeatherRepository`, `MediaSessionRepository`, `NotificationDigestRepository`, `FocusSoundEngine`, `HardwareControlRepository`, `FocusTimerRepository`, and `VoiceMemoRepository`.
- **v15 Baseline Build Metrics:** R8 `minifyEnabled true` (`allowshrinking,allowoptimization` without obfuscation on `com.nothinglauncher.**`), `shrinkResources false`, no app-specific ART `baseline-prof.txt`, total release APK `5,099,967` bytes.
