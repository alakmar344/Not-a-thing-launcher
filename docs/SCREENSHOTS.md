# Screenshots

All screenshots below are generated deterministically by the Paparazzi (layoutlib) tests in
[`app/src/test/java/com/nothinglauncher/ScreensTest.kt`](../app/src/test/java/com/nothinglauncher/ScreensTest.kt)
and [`app/src/test/java/com/nothinglauncher/BigScreenScreensTest.kt`](../app/src/test/java/com/nothinglauncher/BigScreenScreensTest.kt) —
no emulator or device involved. Regenerate them with:

```bash
./gradlew recordPaparazziDebug
```

Output lands in `app/src/test/snapshots/images/`, named `com.nothinglauncher_<TestClass>_<name>.png`.
CI (`.github/workflows/build.yml`) runs `recordPaparazziDebug` on every push/PR and uploads them
as an artifact; `testDebugUnitTest` fails the build if a layout changes without re-recording.

> **Host note:** On Windows hosts where Application Control blocks `libicuuc_stubdata.dll`,
> `recordPaparazziDebug` / Paparazzi `Renderer.prepare` cannot load layoutlib native binaries
> locally; non-Paparazzi unit and Robolectric tests (`VisualTierTest`, `LauncherRuntimeTest`,
> `V13FeaturesTest`, `V14FeaturesTest`) still execute normally.

## Big-Screen Studio (`BigScreenScreensTest`, `PIXEL_C`)

| | |
|---|---|
| ![Tablet Bento Home](../app/src/test/snapshots/images/com.nothinglauncher_BigScreenScreensTest_tabletBentoHome.png) Tablet Bento Studio Home | ![Tablet Desk HUD Cockpit](../app/src/test/snapshots/images/com.nothinglauncher_BigScreenScreensTest_tabletDeskHudCockpit.png) Matrix Desk HUD Cockpit |
| ![Tablet Dock Space](../app/src/test/snapshots/images/com.nothinglauncher_BigScreenScreensTest_tabletEssentialSpace.png) 2-Column Dock Space | |

## Dark mode (`ScreensDarkTest`)

| | |
|---|---|
| ![Home, monochrome icons](../app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_homeMono.png) Home — monochrome icons | ![Home, colour icons](../app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_homeColor.png) Home — colour icons |
| ![Home Lite](../app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_homeLite.png) Home — Lite tier | ![Clock power-on](../app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_clockPowerOn.png) Clock power-on scan |
| ![Widgets showcase](../app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_widgetsShowcase.png) Weather, Media, Timer, Habit & Kinetic Matrix widgets | ![Desk HUD Matrix stage](../app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_deskHudGlyph.png) Matrix Desk HUD — procedural LED stage |
| ![Drawer](../app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_drawer.png) App drawer | ![Customize sheet](../app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_customizeSheet.png) Customize sheet |
| ![Settings](../app/src/test/snapshots/images/com.nothinglauncher_ScreensDarkTest_settings.png) Settings | |

## Light mode (`ScreensLightTest`)

| | |
|---|---|
| ![Home, monochrome icons](../app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_homeMono.png) Home — monochrome icons | ![Home, colour icons](../app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_homeColor.png) Home — colour icons |
| ![Home Lite](../app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_homeLite.png) Home — Lite tier | ![Clock power-on](../app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_clockPowerOn.png) Clock power-on scan |
| ![Widgets showcase](../app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_widgetsShowcase.png) Weather, Media, Timer, Habit & Kinetic Matrix widgets | ![Desk HUD Matrix stage](../app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_deskHudGlyph.png) Matrix Desk HUD — procedural LED stage |
| ![Drawer](../app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_drawer.png) App drawer | ![Customize sheet](../app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_customizeSheet.png) Customize sheet |
| ![Settings](../app/src/test/snapshots/images/com.nothinglauncher_ScreensLightTest_settings.png) Settings | |

For the pre-v11.0 bespoke launcher's screenshot catalog, see
[`archive/SCREENSHOTS.v10.1.md`](archive/SCREENSHOTS.v10.1.md) (historical only — that UI no
longer exists).
