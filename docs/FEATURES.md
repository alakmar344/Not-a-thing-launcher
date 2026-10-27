# Not-a-Thing Launcher: Complete Feature Catalog (Version 10.0 GA)

A comprehensive breakdown of all capabilities, widgets, gesture systems, customisation tools, and visual components in **Not-a-Thing Launcher** (Version 10.0 General Availability).

---

## 1. Multi-Page Workspace & Native 2D Grid

### 1.1 Unified Native 2D Grid (`CellLayout`)
- **Discrete Coordinate Matrix**: Items are positioned precisely on a `(cellX, cellY, spanX, spanY)` 2D coordinate system.
- **Unrestricted Interleaving**: Standard 1×1 app icons, 1×1 circular folders, 2×2 interactive enlarged folders, 2×2 Max Icons, bespoke dot elements, and hosted third-party AppWidgets coexist seamlessly on the same workspace canvas.
- **Scrollable Pages**: `CellLayout` integrates inside `NestedScrollView`, providing clean grid alignment with fluid vertical expansion when large widgets are placed.

### 1.2 Fluid Drag-and-Drop & Drop Zones
- **Spring-Loaded Paging**: Dragging an item to the left or right screen boundary automatically flips to adjacent workspace pages.
- **Dynamic Folder Merging**: Dragging an app over another app displays a pulsing accent outline; releasing merges them into a Nothing dot folder.
- **Folder Expansion**: Dragging an app over an existing folder adds the app to that folder.
- **Top Drop-Zone Bar**: Persistent action zones dynamically respond to dragging:
  - **Remove**: Clears item from home screen without uninstalling.
  - **Uninstall**: Invokes system uninstallation dialog.
  - **App Info**: Opens system application settings.
- **Persistent Dock Integration**: Drag items into and out of the bottom dock with automatic slot reordering.

### 1.3 Horizontal Pagination & Dot Indicator
- **Smooth Paging Physics**: Pages snap cleanly using Android's `PagerSnapHelper`.
- **Morphing Dot Indicator (`PageIndicatorView`)**: Circular dots at the screen bottom dynamically stretch and morph with scroll offset. Tapping any dot jumps directly to that page.
- **Page Management**: Long-press empty space to access the *Home Customisation* sheet to add blank canvases or remove unwanted pages safely.

---

## 2. Bespoke Nothing Elements Suite

All elements are rendered procedurally using hardware-accelerated 2D canvas drawing and the custom `DotFont` engine, fully visibility-gated to ensure zero idle battery consumption:

| Element | Description | Interactive Behavior | Battery Optimization |
|---|---|---|---|
| **Dot-Matrix LED Clock** | LED-panel clock with pulsating red accent colon, local/system time formatting, and live 60-dot seconds ticker. | Tap opens default Clock / Alarms app. | Ticker runnable stops when screen off or obscured (`onVisibilityAggregated`). |
| **Circular Battery Meter** | Compact paper/dark circular ring of 40 dots. Changes to red at ≤15%, dot breathes while charging. | Tap opens Battery Settings. | Animator stops and battery intent unregisters when hidden. |
| **Battery Gauge Hero Tile** | Full-width hero tile displaying DotFont percentage, charging state, and 28-dot circular capacity meter with charging bolt. | Tap opens Battery Settings; Long-press opens element menu. | Pauses battery polling when off-screen. |
| **Procedural Compass Rose** | True heading dial with accent North indicator dot and live degrees numerical readout. | Rotates dynamically with device geomagnetic sensors. | Sensor unregisters when screen is off, drawer is open, or on a page without compass. |
| **Pedometer / Step Counter** | 36-dot radial activity ring with live step count rendered in dot-matrix typography. | Tap opens health / fitness app. | Step listener unregisters on lifecycle pause. |
| **Media Controller Element** | Authentic Nothing music player tile with live track/artist info and real-time playback transport controls (Play/Pause, Next, Previous). | Controls background media playback via `MediaSessionManager`. | Equalizer animation freezes when hidden. |
| **Voice Memo / Recorder** | Integrated dot-matrix voice recorder with live recording waveform, elapsed timer, and audio memo playback. | Tap to record memo; tap again to save and play back. | Recording audio buffers release safely. |
| **Quick Note / Daily Quote** | Dot-matrix scratchpad displaying user-defined quotes or reminders with live accent dot indicator. | Tap opens quick note editor dialog; Long-press opens element menu. | Static rendering; zero background cycles. |
| **Date & Calendar Tile** | Minimalist tile showing month, dot-matrix day number, weekday, and the next active system alarm. | Tap opens System Calendar. | Alarms query updates on system alarm broadcast only. |
| **Procedural Weather Tile** | Live dot-matrix temperature readout, procedural sun, cloud, and rain glyphs, and condition subtitles. | Tap launches Weather / Search. | Network lookup throttled with local cache. |
| **Quick Toggles Element** | 4 quick control discs: Wi-Fi, Bluetooth, Flashlight/Torch (camera hardware level), and Sound Mode. | Single tap toggles system state with haptic feedback. | Event-driven status listeners. |
| **Stopwatch Tile** | Clean dot-matrix precision stopwatch with start, lap, and reset controls. | Interactive lap recording. | Animator pauses when obscured. |
| **Analog Clock Tile** | Minimalist dot-matrix analog clock face with discrete hour, minute, and second hands. | Tap opens Clock app. | Ticks only while visible. |
| **Screen Time / Wellbeing** | Displays daily active screen hours and minutes with a 7-segment dot usage bar. | Tap opens Digital Wellbeing. | Queries system usage stats only on resume. |
| **Storage & RAM Meter** | Real-time storage capacity meter with free GB readout and circular dot ring fullness meter. | Tap opens Storage Settings. | Queried via `StatFs` on resume. |
| **World Clock** | Dual time-zone dot-matrix clock comparing local time with selected international cities. | Tap opens World Clock. | Ticks only while visible. |

---

## 3. App Icons, Max Icons & Nothing Folders

### 3.1 Nothing Monochrome Icon Engine & In-Memory LRU Cache
- Automatically extracts Android 13+ themed adaptive monochrome vector icons.
- Applies high-contrast monochrome disc filters for apps without themed icons.
- **$O(1)$ In-Memory LRU Cache**: 300-entry memory cache eliminates canvas rasterization stalls on cold start and scrolling.
- Option in Settings to toggle between **Mono** (Nothing style) and **Color** (original app icons in clean circular badges).

### 3.2 Authentic Nothing Folders
- **1×1 Circular Folder Discs**: Translucent circular dark disc with a subtle outline holding a 2×2 mini-icon preview grid with NDot typography caption below.
- **2×2 Enlarged Folder Tiles**: Square hero tiles (`bg_tile_dark`) featuring 4 interactive app icons that can be directly tapped to launch, overflow indicator, folder title, and red accent dot.
- **Folder Management Sheet**: Tap any 1×1 folder or the folder header to open the folder sheet. Enlarge/shrink between 1×1 and 2×2, rename, add apps, ungroup/dissolve, and drag apps out of the folder directly onto the workspace.

### 3.3 Max Icons (2×2 Hero Enlargement)
- Expand any pinned app icon into an authentic 2×2 rounded square hero tile (`bg_tile_dark`).
- Large centered monochrome app icon (60dp), bold uppercase label, and Nothing signature red dot accent.

---

## 4. App Drawer & Search

### 4.1 Finger-Tracking Elevation Physics
- Swiping up from anywhere on the home screen pulls up the app drawer.
- Drawer follows finger dragging with deceleration curves.
- Background applies real-time GPU blur (`RenderEffect`) on Android 12+ devices.

### 4.2 Multi-Tier Ranked Search
Search bar ranks installed applications in order of relevance:
1. **Prefix matches** ("sp" → Spotify).
2. **Word boundary matches** ("notes" → Keep Notes).
3. **Substring matches** ("gram" → Telegram).
4. **Initials / Acronym matches** ("yt" → YouTube, "ps" → Play Store).
5. **Web Search Fallback**: If no application matches, displays a one-tap button: *"Search the web for '[query]'"*.

### 4.3 A–Z Alphabet Dot Scroller
- Scrubbing along the right margin jumps directly to letter sections.
- Displays an enlarged dot-matrix letter bubble with tactile haptic ticks.

### 4.4 Recent & Hidden Apps
- **Recent Apps**: Shows the 4 most recently launched apps at the top of the drawer.
- **Hidden Apps**: Any app can be hidden via long-press → *Hide app*. Hidden packages can be viewed and unhidden in *Settings → Hidden apps*.

---

## 5. Long-Press Menus & Sheets

### 5.1 Empty Space Long-Press (`Home Customisation`)
Long-pressing empty space on any workspace page triggers haptic feedback and displays the bottom customisation sheet:
- **Wallpaper & Style**: Select background styling (Nothing Dot Matrix grid, Solid Minimalist OLED black/white, or System Wallpaper) and Theme Mode (System, Dark, Light).
- **Widgets**: Opens the categorized Bespoke Elements and Android AppWidgets picker.
- **Add / Remove Page**: Instant workspace page management.
- **Settings**: Quick shortcut to full launcher settings.

### 5.2 App Long-Press Popup (`AppPopup`)
Long-pressing any app displays:
- **App Shortcuts**: Dynamic shortcuts provided by the app (e.g. "New tab", "Scan QR").
- **Pin to Home / Remove from Home**.
- **Add to Dock / Remove from Dock**.
- **Enlarge (2×2) / Shrink (1×1)**.
- **Add to Folder / Create Folder**.
- **App Info** (direct intent to system package settings).
- **Hide App**.
- **Uninstall** (triggers system confirmation).

---

## 6. Zero-Idle Battery & System Optimization

- **Lifecycle Sensor Unregistration**: `CompassRepository` and `StepRepository` unregister completely when the launcher is backgrounded or screen locks (`onStop`).
- **Drawer Scrubbing Throttling**: Pauses compass sensor updates when pulling down the app drawer (`drawerProgress > 0.05f`).
- **Page-Aware Sensor Throttling**: Pauses compass hardware updates when on a workspace page without a compass element.
- **Custom View Visibility Overrides**: All 9 animated dot views implement `onVisibilityAggregated(isVisible)` and `isShown` checks, terminating runnable loops and animators when offscreen.
- **In-Memory LRU Icon Cache**: 300 pre-rendered bitmaps eliminate frame drops during scrolling and navigation.

---

## 7. Theming & Personalization

- **Pure OLED Dark Mode**: `#000000` deep black canvas for maximum battery savings on OLED displays.
- **Minimalist Light Mode**: `#F6F6F4` bone-white canvas with dark ink typography.
- **Surface Contrast Fixes**: Added `border_subtle` (`#232327`) and `surface_dark` (`#0C0C0E`) to night mode resources.
- **Edge-to-Edge System Insets**: Proper padding for camera cutouts, navigation gesture bars, and status bars.
- **Non-Destructive Schema v3**: Clean upgrades preserving 100% of user data and layouts.
