# AGENTS.md

Instructions for AI coding agents working in this repository. Read this before making changes.

## What this is

Not a Thing Launcher (`com.nothinglauncher`) is a Nothing OS–style Android home screen. Since
**v11.0** it is **stock AOSP Launcher3 with a design layer on top** — it is not a launcher
written from scratch. Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
[`docs/FEATURES.md`](docs/FEATURES.md) first; they describe the current codebase. Everything
under [`docs/archive/`](docs/archive/) describes the pre-v11.0 from-scratch launcher and is
**historical only** — none of those classes exist anymore. Do not use the archive to understand
or justify changes to current code, and do not "restore" anything from it unless a human
explicitly asks for that feature to be rebuilt.

## Hard rule: don't touch vendored AOSP code

`third_party/aosp/**` is an **unmodified** copy of upstream AOSP Launcher3/SystemUI sources
(see `third_party/aosp/README.md` for exact revisions). Never edit files there. If Launcher3's
behaviour needs to change:

- Prefer overriding/extending it from `app/` (subclassing `Launcher`/`SettingsActivity`,
  replacing resources via `res.srcDirs`, registering a `CustomWidgetPlugin`), the same way
  `NothingLauncher` and `NothingSettingsActivity` already do.
- `launcher3/src/overrides/java` (`src_ui_overrides`) is AOSP's own designated extension point
  and the one place outside `third_party/` where Launcher3-adjacent code is expected to live.
- `launcher3/src/config/java` holds stand-ins for platform-generated classes (`Flags`,
  `BuildConfig`); regenerate `Flags.java` with `tools/gen_launcher_flags.py`, don't hand-edit it.
- If something truly requires patching vendored code, say so explicitly and explain why an
  override isn't possible — don't silently fork AOSP sources.

The actual product code to work in is `app/src/main/java/com/nothinglauncher/**`.

## Building and testing

JDK 17 + Android SDK (compileSdk 35, minSdk 26, targetSdk 36) are required.

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK (R8 minified)
./gradlew recordPaparazziDebug   # (re)generate screenshot goldens in app/src/test/snapshots
./gradlew testDebugUnitTest      # unit + Robolectric tests, incl. layout regression vs goldens
```

CI (`.github/workflows/build.yml`) runs `recordPaparazziDebug` *before* assembling — if you
change any layout, string, or drawable that affects a rendered screen, you must re-run
`recordPaparazziDebug` and commit the updated PNGs in `app/src/test/snapshots/images/`, or CI's
`testDebugUnitTest` will fail the diff.

**Before claiming a build or test fix works, actually run the relevant Gradle task and read the
output.** Don't guess from reading code alone — this codebase has previously shipped a Kotlin
compile error straight to CI (null-safety on a nullable `SharedPreferences`, see CHANGELOG) that
a build attempt would have caught immediately.

If your sandbox has a constrained memory cgroup (check `cat /sys/fs/cgroup/memory.max`), the
Gradle daemon can be OOM-killed on dex/R8-heavy tasks like `assembleRelease` even though the
code is fine — GitHub's runners have 16 GB. If a local build dies with
"Gradle build daemon disappeared unexpectedly", first distinguish a real compile/test failure
(read the log for `error:`/`e:` lines or a stack trace with a real exception) from an
environment OOM (no compiler error, daemon just vanishes) before concluding anything is broken.
Lowering `-Xmx`/`-XX:MaxMetaspaceSize` and `--max-workers=1` via
`-Dorg.gradle.jvmargs=...  --no-daemon` can get further in a memory-constrained sandbox.

Measured in a 3 GB cgroup: R8 itself peaks at ~1.7 GB RSS over this app; the OOMs come from
*other* JVMs sitting next to it — most often a leftover Kotlin compile daemon (~1.1 GB, check
`pgrep -af KotlinCompileDaemon` and kill it). With nothing else resident, this completes a full
release build in ~6 min:

```bash
./gradlew assembleRelease -x lintVitalRelease --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx2300m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC" \
  -Dkotlin.compiler.execution.strategy=in-process
```

## Release builds must be checked, not assumed

Debug builds are not minified, so **no unit/Robolectric/Paparazzi test can tell you whether R8
broke the release build.** Launcher3 instantiates several classes purely by name from resources
(`R.array.custom_widget_providers`, `ResourceBasedOverride` `*_class` strings), which R8 can't
see. If you touch any keep rule, add a widget, or add a `*_class` override, build the release
APK and run:

```bash
python3 tools/verify_release_reflection.py --apk app/build/outputs/apk/release/app-release.apk \
  --values app/build/intermediates/incremental/release/mergeReleaseResources/merged.dir/values/values.xml \
  --sources third_party/aosp/launcher3/src launcher3/src app/src/main/java
```

You normally don't need to: it runs automatically at the end of every `assembleRelease`
(`verifyReleaseReflection` in `app/build.gradle`), so CI's existing release step runs it too. It
exists because 11.1 shipped a release build with every Not a Thing widget stripped out while all
tests were green. When you claim a size/perf win from a build change,
measure it on the release artifact (APK and `classes.dex` sizes, dex method/field counts) and say
what you could *not* measure.

Never commit anything under `*/build/` (Gradle output) even though some such directories are
unfortunately already tracked in git history — `git status` before committing and leave build
artifacts alone.

## Hard rule: don't edit `.github/workflows/` from an agent session

The credentials that push agent branches don't have GitHub's `workflow` permission, so GitHub
**rejects the entire push** if it contains any change to a file under `.github/workflows/` —
and because every later commit sits on top of that one, the branch can't be pushed (or turned
into a PR) at all from then on. This has happened repeatedly.

Put CI behaviour where the workflow already reaches it instead:

- Hook it into a Gradle task CI already runs (`recordPaparazziDebug`, `assembleDebug`,
  `assembleRelease`) — e.g. `finalizedBy`/`dependsOn`, the way `verifyReleaseReflection` is
  attached to `assembleRelease` in `app/build.gradle`.
- Logic that isn't Gradle-shaped goes in a script under `tools/` invoked from such a task.

If a change genuinely can only be made in a workflow file, make it in **`ci/workflows/`**
instead (same file name as the live one), which pushes normally. `ci/workflows/` is the source of
truth: `ci/workflows/sync-workflows.yml` mirrors it into `.github/workflows/` after the change is
merged to `main`, using the `WORKFLOW_SYNC_TOKEN` secret (README, "Staged workflows"). Until a
human has done that one-time setup, staged changes are **not live** — check whether
`.github/workflows/sync-workflows.yml` exists, and say plainly in your summary whether your
change is live or only staged. Keep each staged file a superset of its live counterpart, and
never add anything to `ci/workflows/` you wouldn't want running on `main` with the repo's
secrets: once merged it executes with them.

## Docs policy

- `README.md` — project overview, build/signing instructions. Keep it short and current; link
  out to `docs/` for depth instead of duplicating detail.
- `docs/ARCHITECTURE.md`, `docs/FEATURES.md`, `docs/SCREENSHOTS.md` — must describe the
  **current** codebase only. When you change behaviour, update these in the same change, don't
  leave them to drift.
- `CHANGELOG.md` — add an entry under `[Unreleased]` for any user-visible fix or feature in the
  same commit/PR that makes the change. Follow the existing Keep-a-Changelog-ish style (Added /
  Changed / Fixed / Removed).
- `docs/archive/` — append-only historical record. Don't edit old entries; don't add new ones
  unless a whole subsystem is being retired the way the pre-v11.0 launcher was.
- If you discover a doc describing something that no longer exists, fix or archive it in the
  same change rather than leaving it stale for the next person.

## Commit hygiene

- One logical change per commit, with a message explaining *why*, not just *what* (see existing
  `git log` for the house style).
- Run the narrowest Gradle task that actually proves your change (`compileDebugKotlin` for a
  quick compile check, `recordPaparazziDebug`/`testDebugUnitTest` for UI changes,
  `assembleDebug`/`assembleRelease` before claiming the build is fixed) before committing.
