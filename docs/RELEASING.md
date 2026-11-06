# Releases and updates

How a change gets from `main` onto your phone, and what (if anything) you ever need to do by hand.

## What happens automatically

Every push to `main` runs [`.github/workflows/build.yml`](../.github/workflows/build.yml), which:

1. renders the screenshot tests (`recordPaparazziDebug`),
2. builds the debug and release APKs (`assembleDebug`, `assembleRelease`) — the release build
   also runs `verifyReleaseReflection`, which fails the build if R8 stripped a class Launcher3
   loads by name (see [AGENTS.md](../AGENTS.md#release-builds-must-be-checked-not-assumed)),
3. uploads the APKs to the GitHub release, replacing the previous build's files.

Each build installs **over** the previous one, no uninstall needed, because two things are
guaranteed by `app/build.gradle` rather than by the workflow:

| | How |
|---|---|
| Same signing key every time | `keystore/stable-signing.p12` (committed on purpose), unless you supply your own key, see below |
| Always a higher `versionCode` | `2000 + GITHUB_RUN_NUMBER`, so each CI run is newer than the last; `VERSION_CODE` overrides it |

Pull requests run the same build as a check but don't publish anything.

## Installing an update

Open the repo's [Releases](https://github.com/alakmar344/Not-a-thing-launcher/releases) page and
install **`app-release.apk`** from the newest release. `app-debug.apk` is the unminified build
for debugging; the `.png` files are the current screenshots. The version you're running is in
Settings → About.

## Publishing a numbered version

1. Bump `versionName` in `app/build.gradle`.
2. Move the `[Unreleased]` entries in [`CHANGELOG.md`](../CHANGELOG.md) under a new heading for that
   version.
3. Merge to `main`. Optionally also create a tag `vX.Y` on that commit (GitHub → Releases → *Draft a
   new release* → new tag): tag pushes build a separate release named after the tag.

## Known limitation: the rolling release is titled "v10.0"

The live workflow hard-codes `v10.0` as the tag/title for builds from `main`, so up-to-date 11.x
APKs appear under a release called "Release v10.0". The files in it are always current; only the
title is wrong. Automation that pushes this repo isn't permitted to edit `.github/workflows/`, so
this needs a one-time manual edit in GitHub's web editor (≈2 minutes):

1. Open `.github/workflows/build.yml` on GitHub and click the pencil icon.
2. Directly above the `- name: Create Release` step, add:

   ```yaml
         - name: Read versionName
           id: version
           run: echo "name=v$(sed -n 's/^ *versionName "\(.*\)"/\1/p' app/build.gradle)" >> "$GITHUB_OUTPUT"
   ```

3. In the `Create Release` step, replace both occurrences of `'v10.0'` with
   `steps.version.outputs.name`.
4. Commit to `main`.

After that, `main` builds publish to a release named after `versionName` (e.g. `v11.2`), and a
new release appears whenever the version is bumped. Remove this section once it's done.

## Using your own signing key (optional)

The committed key is fine for a sideloaded launcher. If you want a private key instead, decide
**now**: switching keys later forces every user to uninstall once, because Android only accepts
updates signed with the installed app's key.

`app/build.gradle` picks the key in this order:

1. env `RELEASE_KEYSTORE_FILE` + `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
   `RELEASE_KEY_PASSWORD` (the CI workflow would have to pass these from secrets),
2. `keystore.properties` in the repo root (local builds; git-ignored),
3. `keystore/stable-signing.p12` (default).

Check which key signed an APK with:

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## Upgrading from releases before 11.0

Releases v2–v10.0 were each signed with a throwaway key that GitHub Actions generated and
discarded, so they can't be updated in place: uninstall once, then install the current APK.
From 11.0 on, every build installs over the previous one. The 11.x home screen layout also
starts fresh, because stock Launcher3 keeps its own layout database.
