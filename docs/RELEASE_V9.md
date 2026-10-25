# Release v9.0 — Native Launcher Rebuild

**Package:** `com.nothinglauncher`  
**Version:** 9.0 (`versionCode 11`)  
**Min SDK:** 26 · **Target SDK:** 34  
**Artifact:** `dist/Not-a-Thing-Launcher-9.0-release.apk`

## What changed

v9.0 keeps the Nothing OS visual language and replaces the remaining placeholder
behaviour with native Android implementations:

- **Home-screen editing** — drag apps from the drawer, between pages, into the dock,
  onto another app (folder merge), or into a folder. Long-press arms an item; moving
  starts a drag, releasing without moving opens the context menu.
- **Dock** — reorder by dragging, drop apps from pages/folders, and keep intentional
  dock copies without duplicating an app on the workspace.
- **Folders** — create by drop, rename, add/remove apps, dissolve, and drag apps out
  of the folder sheet back onto a page or dock. Folder app menus include Open, Move to
  home, Enlarge, Dock and App info.
- **Widgets** — host real `AppWidgetHost` widgets, run provider configuration, resize
  height, reset to provider height, move within a page, move between pages, and remove.
- **Live elements** — weather via Open-Meteo with a manual city option, media-session
  controls through a notification listener, step counting through the hardware sensor,
  compass, screen time through usage access, countdown, habit tracker, and a real
  microphone voice-memo recorder.
- **Onboarding** — first launch explains the gesture model, offers a theme choice, and
  opens the system HOME-role request. Existing installs are migrated as already
  onboarded so an update never interrupts the user.
- **Optional gestures** — the accessibility service powers double-tap-to-sleep and
  pull-down notifications; both fail with an explanation when the service is off.

## Permissions

All optional capabilities are requested in context and can be reviewed in
**Settings → Permissions & capabilities**.

| Capability | Permission / system access | Used by |
|---|---|---|
| Screen time | Usage access (`PACKAGE_USAGE_STATS`) | Screen Time element |
| Media controls | Notification listener access | Media element |
| Sleep / notification shade | Optional accessibility service | Home gestures |
| Step counter | `ACTIVITY_RECOGNITION` | Pedometer element |
| Voice memos | `RECORD_AUDIO` | Voice Memo element |
| Torch | `CAMERA` | Quick Toggles |
| Weather | `INTERNET`, `ACCESS_NETWORK_STATE` | Weather element |

The launcher is fully usable without any optional permission; elements show honest
empty states instead of fabricated data.

## Upgrade compatibility

- `applicationId` and the release signing key are unchanged from the v9 build line.
- `LauncherPrefs` uses `SCHEMA_VERSION = 2`. Existing `workspace_pages_v3`,
  `home_items_v2`, `dock_items_v2` and `widget_ids` data stays readable.
- First-run onboarding is skipped for existing installs with layout/settings data.
- Removing a page merges its apps, folders, widgets and elements into page 0 rather
  than deleting them.
- Release builds are signed with `keystore/release.jks` (alias `nothinglauncher`);
  keep that file and `keystore.properties` for every future update.

## Build and verify

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

Verify the release certificate and manifest before distribution:

```powershell
& "$env:ANDROID_HOME\build-tools\34.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\app-release.apk
& "$env:ANDROID_HOME\build-tools\34.0.0\aapt.exe" dump badging `
  app\build\outputs\apk\release\app-release.apk
```

The final APK is copied to `dist/` after signature verification.

## Testing note

The project builds and its layout/screenshot tests run without an emulator. Interactive
gesture and permission flows require a physical device or an accelerated emulator; this
workspace's emulator could not start because the host hypervisor driver is unavailable.
