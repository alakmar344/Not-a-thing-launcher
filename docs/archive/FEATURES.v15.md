# Features (v15 Snapshot)

> **Historical snapshot — superseded by [`../FEATURES.md`](../FEATURES.md) in v15.5.**

v15 paired AOSP Launcher3 (`android-14.0.0_r75`) with:

- **StandBy & Consoles:** `NothingDreamService` OLED charging clock, 4-stage `DeskHudActivity` (`COCKPIT`, `CLOCK`, `FOCUS`, `MATRIX`), `GlyphMatrixCanvasView`, and 2-column `w600dp`+ Big-Screen Bento Studio layouts.
- **13 Dot-Matrix Widgets:** `ClockWidget` (with World Clock), `BatteryCalendarWidget`, `WeatherWidget` (Open-Meteo 12h sparkline + rain baseline), `MediaWidget`, `QuickTogglesWidget`, `FocusTimerWidget`, `EssentialNoteWidget`, `HabitMatrixWidget`, `VitalsWidget`, `CompassStepWidget`, `SmartFolderWidget`, `DeskBriefingWidget`, and `KineticMatrixWidget` (`GRAVITY`, `LIFE`, `SNAKE`).
- **Search, Drawer & Visuals:** Offline `CommandEngine` (`/`), 3-mode `NothingAllAppsContainerView` (`GRID` / `LIST` / `CATS`) + Work-tab support, fail-closed `HiddenVaultSheet` (`BiometricPrompt` / `VaultAuthActivity`), 5 `AccentTheme` palettes (`CustomizeSheet` inline strip + Settings Studio 15), `MONO` / `COLOR` / `DOTS` icon styles + ADW/Nova icon packs, `WidgetSurface` overrides, `DotGridDrawable`, and `VisualTier` scaling.
