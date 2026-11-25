> **Archived.** Historical release notes for **v12** of Not a Thing Launcher (superseded by v13).
> See [`../../CHANGELOG.md`](../../CHANGELOG.md) for the current changelog.

# Release v12: Fits the phone, and a bit more character

**Package:** `com.nothinglauncher`  
**Tag:** `v12` · **Min SDK:** 26 · **Target SDK:** 36  
**Merged PR:** [#29](https://github.com/alakmar344/Not-a-thing-launcher/pull/29)

## Highlights (historical v12)

- **Visual quality tiers (`Lite`, `Balanced`, `Full`):** `VisualTier` classified device RAM and CPU cores automatically (`Auto`) or per user selection under Settings → Look → Visual quality, plus automatic downgrade to `Lite` after an Android 11+ low-memory exit (`ApplicationExitInfo`).
- **Backdrop light pool (`Full`):** Ambient top glow across the dot-grid backdrop (`DotGridDrawable`).
- **LED clock power-on sweep:** Red scan line illuminating digits after 5+ minutes away (`DotMatrixClockView.playPowerOn()`).
- **Tactile press tiles & frosted sheets:** Subtle sink/rebound touch animation + haptics (`Tactile`) and Android 12+ blur on supported `Full`-tier devices (`Sheets`).
- **Android 17 memory-limit hardening & fixes:** Cache eviction on `onTrimMemory`, Android 8.0/8.1 widget registration API guard, light-mode switch contrast fix, and cut-out weather moon vector.
