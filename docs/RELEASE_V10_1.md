# Release v10.1: App Icon, Painless Updates & Polish

**Package:** `com.nothinglauncher`  
**Version:** 10.1 (`versionCode` = 2000 + CI run number, always higher than any earlier build)  
**Min SDK:** 26 · **Target SDK:** 34

## What's new

### Real app icon, no more digging through Settings
Before 10.1 the launcher only registered as a *home* activity. After installing:
- it had **no icon** in your phone's app list,
- the installer's **Open** button was greyed out,
- the only way to turn it on was *Settings → Apps → Default apps → Home app*.

10.1 adds an app icon (`LauncherEntryActivity`, an invisible trampoline). Tapping it:
| Situation | What happens |
|---|---|
| First launch | Welcome tour with a one-tap **Set as default home** button |
| Not the default home | Android's native "choose your home app" prompt |
| Already the default | Straight to the home screen |

The launcher's own app drawer still hides this icon, so it never shows up twice.

### App-icon shortcuts
Long-press the icon in any launcher for:
- **Launcher settings**
- **Set as default home app**

### Updates install over the old version
Every build is now signed with one permanent key and gets a higher `versionCode`. Just install the new APK over the old one. Your layout and settings are kept. If you installed **v10.0 or older** from GitHub, uninstall it one last time first, because those builds were signed with throwaway keys that no longer exist.

### Polish
- Welcome tour: once the launcher is already your home app, the redundant *"Continue without changing home"* button is hidden. The primary button reads **Open home screen**.
- App-shortcut icons use a white glyph on a black disc, so they stay legible on light shortcut menus.
