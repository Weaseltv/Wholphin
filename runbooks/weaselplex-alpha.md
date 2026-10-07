# WeaselPlex Alpha development

Make a change, build and install Alpha, then let the owner verify and request the
next tweak. Customers receive an update only when the owner
asks to publish a stable release. An Alpha build never creates a GitHub release or
tag and never changes Latest or the Downloader link.

## Fast change → build → install → owner verification

Routine Alpha iterations skip optional tests, lint, formatting/pre-commit suites,
extra reviews, APK inspections/checksums and visual/device checks unless the owner
explicitly requests them. Compilation and the helper's existing Alpha-package and
native-dependency safeguards remain. Do not gate an Alpha install on a clean tree,
commit, PR, merge or CI result. Track finalized changes afterward.

On the assigned VPS, from the working branch containing the change:

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
./scripts/build-alpha.sh --install 100.105.135.30:5555
```

This builds arm64 Alpha, connects directly over Tailscale and installs in place.
When ADB returns **Success**, report the change and installation and hand the
iteration back to the owner. Do not open the app, send remote input, inspect the
launcher, take screenshots, query installed package metadata or test playback/
D-pad behavior. Device diagnostics are only for actual connection/install errors.
The owner performs visual checks and requests the next change.

Keep Tailscale connected and network debugging enabled on the Shield. The target
is `100.105.135.30:5555` (`shield.taildbfaa7.ts.net`), reached directly from VPS
`srv1160496` (`100.72.23.45`). The owner approves ADB authorization if prompted.
Do not use ThinkCentre or another machine for builds, transfers or ADB without
explicit owner approval; a connection failure does not authorize a fallback.

The helper builds only arm64-v8a, skips release code/resource shrinking, and keeps
Gradle's incremental outputs across tasks for subsequent builds. The first build on a fresh
worktree is slower. It prints the path to `WeaselPlex-alpha-arm64-v8a.apk` and a
build record with the commit, build time and any uncommitted changes.

If the intended Alpha APK is already built, install it without rebuilding:

```bash
adb connect 100.105.135.30:5555
adb -s 100.105.135.30:5555 install -r app/build/outputs/apk/weaselfin/alpha/WeaselPlex-alpha-arm64-v8a.apk
```

For a build without installation, use `./scripts/build-alpha.sh`. For another
device architecture, use `--abi armeabi-v7a` or `--abi x86_64`.

Android Studio can also build the `weaselfinAlpha` variant. The equivalent direct
Gradle command is:

```bash
./gradlew :app:assembleWeaselfinAlpha -PWeaselPlexTargetAbi=arm64-v8a
```

## Live Home layout tuner (Alpha only)

Open **Settings → Customize home page → Live layout tuner (Alpha)**. Select a
Home row and choose whether edits apply to that row or all rows. Up/down selects a
control; left/right adjusts numeric values by 1 dp, immediately in the full Home
preview. The fixed readout shows the current row's exact values for screenshots.

Controls include card height, spacing between cards, base vertical padding,
extra vertical padding, divider-to-padding gap, space after the row, row side
padding, separate row end padding and title-to-divider gap. Row side padding
sets the left/start position; row end padding (0–160 dp) adds room after the last
card without moving the first card. Existing end spacing stays unchanged until
adjusted. Card shape, image fit and captions are also
adjustable. Divider thickness (1–8 dp), glow spread (0–48 dp), and glow strength
(0–100) are live controls too; 0 strength/spread disables glow. Base and extra padding apply both above and below the cards; the
divider-to-card distance is the divider gap + base padding + extra padding.
All padding values are absolute dp; Picks starts with 9 dp extra padding.

Use **Hide controls / inspect Home** to focus the cards and see their glow;
**Back** brings the overlay back. **Save & close** (or Back from the controls)
saves the values in the usual local per-user Home settings. **Discard this tuning
session** restores the settings from when the tool opened.

WeaselPlex Home defaults are 172 dp card height and 22 dp between cards. Existing
customized rows retain their saved values; **Set every row to 172 dp / 22 dp**
applies those values to the current layout without resetting its other options.
The tuner entry is hidden from customer builds.

## Alpha installation and build host

- Launcher name: **WeaselPlex Alpha**.
- Launcher icon and TV banner include an **ALPHA** badge so the two installed
  apps are easy to distinguish. These resources are scoped to the Alpha build.
- Package: `tv.theweasel.weaselplex.alpha`, alongside customer
  `tv.theweasel.weaselplex`.
- Same WeaselPlex branding, server defaults, Seerr features and native playback
  libraries as the customer flavor. Alpha requires ffmpeg, AV1 and MPV libraries;
  a stub-only test build is refused.
- Customer update prompts and update installation controls are disabled in Alpha.
  Install subsequent Alpha APKs manually or with ADB.
- Alpha has its own local login/settings. Sign in once. Reinstalling Alpha keeps
  those settings; server-side watch history still belongs to your account.
- Version names end in `-alpha`. Alpha's versionCode stays at 1 so testing another
  branch or an older commit does not require uninstalling it.
- Alpha uses the build host's debug keystore, with no production signing key
  required. Keep building on the same host. Changing debug signing keys requires
  uninstalling Alpha and loses its local settings. For multiple build hosts,
  provision the same **non-production** debug key privately on those hosts.
- Configure the Android SDK and the usual JDK/Gradle dependencies on the host.
  Native extensions come from the existing host-only
  `WholphinExtensionsUsername` / `WholphinExtensionsPassword` settings in
  `~/.gradle/gradle.properties`, or all three local AARs in `app/libs`.
  Keep keys and credentials out of the repo.

## Projectivy launcher artwork (owner reference)

An installed APK's icon/banner and Projectivy's displayed tile can differ. On the
owner's Shield, installing the badged Alpha APK and restarting Projectivy still
left an unbadged Alpha tile. Assigning the Alpha banner directly fixed it:

1. Long-press **WeaselPlex Alpha** and confirm that exact name in the menu.
2. Choose **Change icon** → **From picture** and select the Alpha master from
   `art/alpha-launcher/tv-banner.png`, copied to the Shield. The current copy is
   `/sdcard/Pictures/WeaselPlexAlpha/WeaselPlex-Alpha-banner.png`.
3. Return to the launcher and verify the visible **ALPHA** badge beside the
   customer app. Keep that picture on the Shield for the launcher to use.

Preserve the owner's launcher layout and other tiles. Do not clear launcher data
to refresh an icon. These steps are for the owner; agents only troubleshoot the
launcher when explicitly asked. Successful installation does not establish what
Projectivy displays; leave that verification to the owner.

## Accumulate changes, then ship once

1. Start development from `weaselfin`. Use a feature branch for one change, or a
   shared `weaselfin-alpha` branch for a batch of changes. Merge completed feature
   branches into that batch branch so every Alpha build contains the whole batch.
2. Make fixes, build Alpha and install directly on the Shield. The owner tests and
   requests further tweaks; repeat for as many days as useful. Commit finalized
   work and track release notes without delaying the owner's installed build.
3. Before shipping, commit the finalized changes. If the owner requests another
   Alpha verification build, install it for the owner to test. If the batch includes
   unfinished work, finish or revert it first; promotion includes everything merged.
4. When the owner explicitly asks to ship, merge the finalized batch into
   **`weaselfin`**, tag that merge commit `vX.Y.Z`, then build the signed
   `assembleWeaselfinRelease` APK from the tag on the established signing host.
   This rebuild uses the same finalized source with the customer package,
   production key, tag-derived versionCode and release optimization. Do not
   upload or rename the Alpha APK as a customer release.
5. Follow every release check in `AGENTS.md`: versionCode above current Latest,
   signing certificate starting `132905a2`, ffmpeg bundled, exact `WeaselFin`
   asset names, plain `vX.Y.Z` title, useful release notes for the whole batch,
   Latest marked correctly, Downloader redirect and in-app updater verified.
   Release optimization differs from Alpha; playback/performance bugs may need a
   final signed-build check before publishing. No pre-merge device check is required.
6. Bring the development branch up to date with `weaselfin` for the next batch,
   update the existing WeaselPlex Product in Notion, and clean release/abandoned
   outputs while retaining the active Alpha worktree's incremental state.

Keep the active Alpha worktree's `app/build`, root `build`,
`wholphin-mpv-stub/build`, `.gradle` and `.kotlin` across tasks. Do not run `clean`
or delete caches after each install. Overwrite the current APK instead of archiving
every iteration. Remove throwaway APK copies and abandoned worktrees when the
batch ends, the owner requests cleanup or disk pressure requires it. Preserve the
host debug key and shared Gradle dependency cache. Check `df -h ~` after builds
and report less than 30 GB free. This is the Alpha exception to generic disk
cleanup in `AGENTS.md`; stable release cleanup/checks retain their requirements.
