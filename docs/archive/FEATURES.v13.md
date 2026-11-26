> **Historical (v13)** — archived on transition to **v14** (Big-Screen Studio, 4-stage Glyph Desk HUD, Widget Surfaces, Smart Folder Max Icon, and Drawer Categories). Kept for historical reference; see [`../FEATURES.md`](../FEATURES.md) for current features.

---

# Features (v13)

Not a Thing Launcher v13 paired stock **AOSP Launcher3** (`android-14.0.0_r75`) with a Nothing OS–inspired dot-matrix surface, interactive telemetry, the `-1` Essential Space hub, offline Command Search, and granular personalization.

## Summary of v13 capabilities

- **Visuals, Accents & Physics:** 5 hardware accent palettes (`AccentTheme`), `DotGridDrawable` parallax/ripples/night-dimming, Monochrome/Colour/13×13 Dot-Matrix Glyph icons (`IconFactory` + `IconPackManager`), `SpringAnimation` tactile physics + optional 4 ms synthesized click audio.
- **`-1` Essential Space Overlay:** Phone single-column feed with Daily Briefing, 4 Hardware Toggles, Scratchpad Note, Voice Memos, Focus Timer/Stopwatch, and Device Vitals.
- **11 Dot-Matrix Widgets:** `ClockWidget`, `BatteryCalendarWidget`, `WeatherWidget`, `MediaWidget`, `QuickTogglesWidget`, `FocusTimerWidget`, `EssentialNoteWidget`, `HabitMatrixWidget`, `VitalsWidget`, `CompassStepWidget`, and `SmartFolderWidget`.
- **Command Search, Smart Drawer & Vault:** Offline calculator/converter/timezone/fuzzy `CommandEngine`, Grid ↔ List drawer toggle with recent apps strip, long-press *Hide in Vault* and *Rename* shortcuts, configurable home gestures + optional `NothingLockAccessibilityService`, 4 starter presets, and `.nothing.json` backup/restore via `LauncherEntryActivity`.
