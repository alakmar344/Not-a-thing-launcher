# Release v15 — Work-Tab Isolation, Live Cross-Surface Sync, World Clock & Studio UX Polish

**Tag:** `v15`  
**Status:** Superseded by **v15.5** (Deep Runtime, Rendering, Memory, IPC & Release Binary Performance Optimization).

> Preserved as an immutable release snapshot of the v15 release. For current release notes, see [`CHANGELOG.md`](../../CHANGELOG.md) and [`RELEASING.md`](../RELEASING.md).

## Highlights of v15

- **Live Cross-Surface Synchronization (`NothingLauncher`, `CustomizeSheet`, Settings, Widgets):** Inline 5-dot Hardware Accent Swatch strips in `CustomizeSheet` and Settings (**Studio 15**), `OnSharedPreferenceChangeListener` propagation across Home, Drawer, `-1` Dock Space, and all 13 widgets, plus instant light/dark/focus backdrop updates.
- **Work-Profile Drawer Isolation & World Clock (`NothingAllAppsContainerView`, `DotMatrixClockView`):** Separated `MAIN`, `WORK`, and `SEARCH` drawer adapters cleanly (supporting Vault filtering, `LIST`, and `CATEGORIES` on Work profiles), added `timeZone` support to `DotMatrixClockView`, and gated `glow` on `VisualTier.effects().dotGlow`.
- **Kinetic Matrix & StandBy Craft:** Full touch interaction and visibility gating across `GRAVITY`, `LIFE`, and `SNAKE` modes in `KineticMatrixView`, `NothingDreamService` OLED StandBy screensaver, structured Desk Note cards, and `AudioTrack.Builder` stereo synthesis in `FocusSoundEngine`.
- **v15 Release Baseline (pre-v15.5 optimization):** `app-release.apk` = `5,099,967` B (`classes.dex` + `classes2.dex` = `3,477,852` B uncompressed, `resources.arsc` = `2,528,776` B, `22,301` defined / `27,155` referenced DEX methods).
