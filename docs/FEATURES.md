# Not-a-Thing Launcher: Complete Feature Catalog

A comprehensive breakdown of all capabilities, widgets, gesture systems, customisation tools, and visual components in **Not-a-Thing Launcher** (Version 8.0).

---

## 1. Multi-Page Workspace & Navigation

### 1.1 Horizontal Multi-Page Workspace
- Swipe left and right smoothly between unlimited workspace pages.
- Pages snap cleanly into viewport using Android's `PagerSnapHelper`.
- Each page is an isolated workspace canvas hosting bespoke Nothing elements, third-party Android AppWidgets, standard 1×1 app icons, and enlarged 2×2 Max Icons.

### 1.2 Minimalist Dot Page Indicator (`PageIndicatorView`)
- Displays an array of circular dots at the bottom of the home screen representing workspace pages.
- The active dot dynamically morphs/elongates in real-time as the user scrubs between pages.
- Tapping any dot instantly navigates directly to that page.

### 1.3 Page Management
- Long-press any empty space on any home screen page to open the **Home Customisation** sheet.
- **Add Page**: Appends a new blank workspace canvas.
- **Remove Page**: Safely deletes the current empty or populated workspace page (disabled when only 1 page remains).

---

## 2. Bespoke Nothing Elements Suite

All elements are rendered procedurally using hardware-accelerated 2D canvas drawing and the custom `DotFont` engine:

| Element | Description | Interactive Behavior |
|---|---|---|
| **Dot-Matrix LED Clock** | Authentic Nothing LED-panel clock with pulsating red accent colon, local/system time formatting, and live 60-dot seconds ticker. | Tap opens default Clock / Alarms app. |
| **Circular Battery Meter** | Compact paper/dark circular ring of 40 dots. Automatically registers sticky battery intent; changes to red at ≤15%, dot breathes while charging. | Tap opens Battery Settings. |
| **Battery Gauge Tile (v8.0)** | Full-width hero tile displaying DotFont percentage, status/charging state, and a 28-dot circular capacity meter with charging bolt. | Tap opens Battery Settings; Long-press opens element menu. |
| **Quick Note / Quote (v8.0)** | Signature dot-matrix scratchpad displaying user-defined quotes or reminders with live accent indicator. | Tap opens quick note editor dialog; Long-press opens element menu. |
| **Date & Calendar Tile** | Minimalist tile showing month, dot-matrix day number, weekday, and the next active system alarm. | Tap opens Google/System Calendar. |
| **Procedural Weather Tile** | Live dot-matrix temperature readout, animated/procedural sun, cloud, and rain glyphs, and condition subtitles. | Tap launches Weather / Search. |
| **Quick Toggles Element** | 4 quick control discs: Wi-Fi, Bluetooth, Flashlight/Torch (camera hardware level), and Sound Mode (Normal / Vibrate / Silent). | Single tap toggles system state with haptic feedback. |
| **Pedometer / Step Counter** | 36-dot radial activity ring with live step count rendered in dot-matrix typography. | Tap opens health / fitness app. |
| **Procedural Compass Rose** | True heading dial with accent North indicator dot and live degrees numerical readout. | Rotates with device geomagnetic sensors. |
| **Screen Time / Wellbeing** | Displays daily active screen hours and minutes with a 7-segment dot usage bar. | Tap opens Digital Wellbeing. |
| **Storage & RAM Meter** | Real-time internal storage capacity meter with free GB readout and circular dot ring fullness meter. | Tap opens Storage Settings. |
| **World Clock** | Dual time-zone dot-matrix clock comparing local time with UTC/London. | Tap opens World Clock. |

---

## 3. App Icons, Max Icons & Folders

### 3.1 Nothing Monochrome Icon Engine
- Automatically extracts Android 13+ themed adaptive monochrome vector icons.
- For third-party apps without themed icons, applies an automated high-contrast monochrome disc filter.
- Option in Settings to toggle between **Mono** (Nothing style) and **Color** (original app icons in clean circular badges).

### 3.2 Max Icons (2×2 Hero Enlargement)
- Long-press any pinned app icon on the home screen to reveal the popup menu.
- Select **Enlarge (2×2)** to expand the icon into a prominent 4-cell hero disc.
- Select **Shrink (1×1)** to return the icon to standard grid sizing.

### 3.3 Workspace App Folders
- Group multiple apps into a single Nothing dot folder tile.
- 4 mini-icon preview grid inside the folder icon disc.
- Tapping opens the folder manager modal with folder renaming and app launching.

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
- App Shortcuts (dynamic shortcuts provided by the app, e.g. "New tab", "Scan QR").
- Pin to Home / Remove from Home.
- Add to Dock / Remove from Dock.
- Enlarge (2×2) / Shrink (1×1).
- Add to Folder / Create Folder.
- App Info (direct intent to system package settings).
- Hide App.
- Uninstall (triggers system confirmation).

---

## 6. Theming & Personalization

- **Pure OLED Dark Mode**: `#000000` deep black canvas for maximum battery savings on OLED displays.
- **Minimalist Light Mode**: `#F6F6F4` bone-white canvas with dark ink typography.
- **Accent Palette**: Toggle between Nothing Red (`#D71921`) and Minimalist White (`#FFFFFF`) for alerts, notifications, and live meters.
- **Grid Density**: Choose between 3, 4, or 5 icon columns.
- **App Labels**: Independent toggle to show or hide app names under icons.
- **Seconds Ticker**: Toggle live 60-dot clock seconds strip.
- **Double-Tap to Sleep**: Double-tap any empty space on home to lock the screen.
- **Swipe-Down Action**: Pull down anywhere on home to open Notification Shade or launch Search.
