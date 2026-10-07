# WeaselPlex Alpha development

Build and test as often as needed. Customers receive an update only when the owner
asks to publish a stable release. An Alpha build never creates a GitHub release or
tag and never changes Latest or the Downloader link.

## Build for the Shield

From the working branch containing the changes:

```bash
./scripts/build-alpha.sh
```

The helper builds only arm64-v8a, skips release code/resource shrinking, and keeps
Gradle's incremental outputs for subsequent builds. The first build on a fresh
worktree is slower. It prints the path to `WeaselPlex-alpha-arm64-v8a.apk` and a
build record with the commit, build time and any uncommitted changes.

Install that APK using your usual sideload method, or install over ADB:

```bash
adb connect SHIELD_IP:5555
./scripts/build-alpha.sh --install SHIELD_IP:5555
```

Enable the Shield's developer options and network debugging, and approve the host
on the Shield first. `--install` needs an already-connected device; the build
helper does not connect to a device automatically. For another device architecture,
use `--abi armeabi-v7a` or `--abi x86_64`.

Android Studio can also build the `weaselfinAlpha` variant. The equivalent direct
Gradle command is:

```bash
./gradlew :app:assembleWeaselfinAlpha -PWeaselPlexTargetAbi=arm64-v8a
```

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

## Projectivy launcher artwork

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
to refresh an icon. Report artwork packaged in an APK, successful installation,
and actual launcher visual verification as separate checks.

## Accumulate changes, then ship once

1. Start development from `weaselfin`. Use a feature branch for one change, or a
   shared `weaselfin-alpha` branch for a batch of changes. Merge completed feature
   branches into that batch branch so every Alpha build contains the whole batch.
2. Make fixes, commit them, build Alpha, and test on the Shield. Repeat for as many
   days as useful. Track the changes for the eventual customer release notes.
3. Before shipping, commit the finalized changes and test an Alpha build of that
   commit. Its build record should show a clean working tree. If the batch includes
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
   update the existing WeaselPlex Product in Notion, and clean build outputs.

Keep incremental outputs while actively iterating. At the end of a build task,
remove its generated `app/build`, root `build`, `wholphin-mpv-stub/build`,
`.gradle` and `.kotlin` outputs and throwaway APKs per `AGENTS.md`. Remove any
throwaway build worktree as well. Check `df -h ~` and report less than 30 GB free.
