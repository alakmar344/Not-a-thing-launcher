# Release v9.0 — Native Launcher Rebuild & CellLayout Grid

**Package:** `com.nothinglauncher`  
**Version:** 9.0 (`versionCode 11`)  
**Min SDK:** 26 · **Target SDK:** 34  
**Artifacts:**
- `app/build/outputs/apk/debug/app-debug.apk` (6.77 MB)
- `app/build/outputs/apk/release/app-release.apk` (5.02 MB)

---

## What Changed in v9.0

v9.0 is a complete rebuild of the internal workspace model from segmented layout stacks into a true native Android Launcher architecture (`CellLayout`), styled with authentic Nothing OS aesthetics:

### 1. Unified Native Android Grid (`CellLayout`)
- Replaced the legacy 5-container `LinearLayout` stack (`elementsContainer`, `widgetContainer`, `enlargedContainer`, `foldersContainer`, `homeGrid`) with a single custom Android `ViewGroup` (`CellLayout`).
- Implements discrete 2D grid coordinate placement `(cellX, cellY, spanX, spanY)`.
- Eliminates artificial container barriers: apps, 1x1 folders, 2x2 enlarged tiles, bespoke Nothing widgets, and AppWidgets can be freely interleaved anywhere on the page grid.

### 2. Authentic Nothing OS Folders
- **1x1 Circular Folder Icons** (`item_workspace_folder_1x1.xml`): Translucent circular dark disc with a subtle outline (`bg_folder_icon.xml`) holding a 2x2 mini-icon grid with Nothing typography caption below.
- **2x2 Enlarged Folders** (`item_workspace_folder_2x2.xml`): 2x2 square tile (`bg_tile_dark`) featuring 4 interactive app icons that can be directly tapped to launch, overflow indicator, folder title, and red accent dot.
- **Folder Management**: Enlarge/shrink between 1x1 and 2x2, rename, add apps, ungroup/dissolve, and drag apps out of the folder sheet directly onto any workspace cell or dock.

### 3. True 2x2 Enlarged App Tiles (Max Apps)
- Refactored `item_max_app.xml` from flat horizontal row banners into authentic 2x2 rounded square tiles (`bg_tile_dark`).
- Large centered monochrome app icon (60dp), bold uppercase label, and Nothing signature red dot accent.

### 4. Native Drag & Drop Interactions
- **Folder Merge**: Dragging an app over another app icon shows a real-time pulsing accent ring; dropping automatically creates a merged folder.
- **Folder Add**: Dragging an app over an existing folder adds the app to the folder.
- **Cell Placement**: Dragging onto empty grid cells shows a dashed bounding slot; dropping places the item at that exact `(cellX, cellY)` coordinate.
- **Top Drop Zones**: Real-time hover detection for Remove (from home), Uninstall (system dialog), and App Info.
- **Dock Integration**: Drag and drop into or out of the persistent dock with slot reordering.

### 5. Zero Data Loss Upgrade Migration
- `LauncherPrefs.kt` and `WorkspacePage.kt` serialize and parse unified `WorkspaceItem`s with an automatic grid allocator (`allocateGridPositions`).
- Upgrades from earlier versions seamlessly migrate legacy app lists, elements, widgets, and folders into the 2D grid matrix without losing user configurations or pinned layouts.

### 6. GitHub Actions Release Workflow
- Updated `.github/workflows/build.yml` to automatically release and tag builds as `v9.0`.
- Integrated Paparazzi layoutlib screenshot verification tests (`ScreensTest.kt`).

---

## Permissions & System Access

All optional capabilities are requested in context and can be reviewed in **Settings → Permissions & capabilities**:

| Capability | Permission / system access | Used by |
|---|---|---|
| Screen time | Usage access (`PACKAGE_USAGE_STATS`) | Screen Time element |
| Media controls | Notification listener access | Media element |
| Sleep / notification shade | Optional accessibility service | Home gestures |
| Step counter | `ACTIVITY_RECOGNITION` | Pedometer element |
| Voice memos | `RECORD_AUDIO` | Voice Memo element |
| Torch | `CAMERA` | Quick Toggles |
| Weather | `INTERNET`, `ACCESS_NETWORK_STATE` | Weather element |

---

## Build and Verify

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```
