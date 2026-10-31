# Agent standing rules (Wholphin / WeaselPlex Android TV)

- This is the WeaselTV fork of Wholphin (WeaselPlex Android TV). Keep customer-facing naming WeaselPlex.
- Update WeaselTV Command Center / Products in Notion as part of finishing work.
- Notion MCP: Claude is Connected on T3 workers. Codex needs a one-time `codex mcp login notion` on that machine (OAuth is machine-local).
- Do not invent new Products from chat folders. Link work to existing Products rows.
- No production secrets in the repo.

## Machines: work on the machine the thread was opened on (owner, 2026-10-12)

The owner works on this project from three T3 machines and spreads threads between
them to balance load: the VPS (`srv1160496`, `100.72.23.45`), the ThinkCentre
(`justin-ThinkCentre-M73`, SSH alias `thinkcentre`, `100.87.100.109`) and the
OVH worker (`t3-ovh`, SSH alias `t3-ovh`, `100.115.38.48`; added 2026-10-31).
None is the default. Follow these rules even when Notion is unavailable or has
not been read.

- Do all of a thread's work on the machine the owner opened it on: editing,
  builds, tests, APK transfers and Shield installs. Run `hostname` if unsure.
- Identify a machine by hostname, never by matching an IP address to
  documentation: addresses get reused. 135.148.43.25 was the old production
  server until 2026-10-27 and is now t3-ovh.
- Never move a thread's work to another machine. That includes falling back
  when a build or ADB connection fails, the machine is busy, or an old procedure
  or Notion log names another machine. Stop and report instead. Move work only
  when the owner says so for that task.
- The one routine cross-machine action is the git refresh below. It only
  fetches and fast-forwards; it never builds, edits or transfers files.

### Keep all three machines' code current

All three machines keep a main checkout at `~/work/weaselfin/Wholphin`, and T3
worktrees branch from it. That copy must never be more than a day behind GitHub.
On 2026-10-12 the ThinkCentre's copy was four weeks old.

- Start every task from fresh code: `git fetch --all --prune`, then branch from
  `origin/weaselfin`, not a local `weaselfin` that may be stale. If the main
  checkout is more than a day behind, run `./scripts/sync-machines.sh` first.
- Finishing a task that pushed or merged code includes refreshing all three
  machines: push your branch first (GitHub is the only transfer; never copy code
  between machines), then run `./scripts/sync-machines.sh`. It updates this
  machine and the other two (VPS, ThinkCentre and t3-ovh). If one can't be
  reached, it says so and still updates the other.
- The script never discards changes or touches other threads' worktrees. Report
  anything it couldn't update, and say in your report if a machine
  could not be reached.
- All three machines sign Alpha builds with the same non-production debug key
  (`~/.android/debug.keystore`, mode 0600; synced 2026-10-12, copied to t3-ovh
  2026-10-31 with the owner's approval), so an Alpha from any of them installs
  over the others'. Never commit it or copy the release key.

### Sending files to the owner's Windows PC: Taildrop

The owner's Windows PC is `desktop-gualr4f` (`100.72.194.48`) on the same
Tailscale network. It has no SSH, so never try `ssh`/`scp`, and never send files
as T3 thread attachments instead. When the owner asks for files on their PC
(for example "save to `C:\Users\justi\Desktop\...`"), send them with Taildrop
from the thread's machine:

```bash
tailscale file cp <file> [<file> ...] desktop-gualr4f:
```

Taildrop delivers to the PC's Downloads folder; agents cannot choose the
Windows folder. Say so in the report and give the target folder the owner asked
for. Taildrop files *from* the PC arrive with `tailscale file get <dir>`.

## Android ship workflow

### Alpha testing and release cadence

- Follow this workflow even when Notion is unavailable or has not been read.
  These instructions and `runbooks/weaselplex-alpha.md` are kept in the repo.
- Routine fixes/changes default to **Alpha testing**, not a customer release.
  Build with `./scripts/build-alpha.sh` (arm64-v8a for the Shield), or
  `assembleWeaselfinAlpha`. See `runbooks/weaselplex-alpha.md`.
- Alpha installs as **WeaselPlex Alpha** (`tv.theweasel.weaselplex.alpha`),
  preserves its own settings across Alpha installs, and has customer updating
  disabled. Do not publish Alpha APKs under the stable release asset names.
- Accumulate finalized changes on a development/batch branch. Only publish a
  stable customer release when the owner explicitly asks to ship/publish it;
  asking for an APK to test does not authorize a customer release.
- Asking to merge a PR does not authorize a customer release either. Merge the
  code without creating a release tag or publishing APKs unless separately asked.

### Fast Alpha iteration (owner policy, 2026-10-07)

- Optimize routine Alpha tweaks for turnaround: make the change, build only the
  arm64 Alpha APK, install it from the thread's machine, and hand it back to the owner.
  Build completion and `adb install -r` returning `Success` finish the iteration.
- Skip optional checks unless the owner explicitly requests them: writing or
  running tests, lint, formatting/pre-commit suites, extra reviews, APK signature/
  checksum/native-library inspections, screenshots and visual checks. Keep the
  build helper's existing Alpha-package/native-dependency safeguards.
- The owner performs Alpha verification. Do not launch/navigate the app, send
  remote input, inspect the launcher, capture screens, query installed package
  metadata or run playback/D-pad/device tests before or after installation.
  Use device diagnostics only to resolve an actual connection/install failure.
- Do not require a clean tree, commit, PR, merge or CI result before delivering
  an Alpha tweak. The helper records the source and uncommitted changes. Track
  finalized changes afterward without delaying the owner's installed build.
- Keep the active Alpha worktree's build outputs and Gradle/Kotlin caches across
  tasks. Do not run `clean` or delete incremental state after each install.
  The Alpha cache exception below overrides the generic cleanup requirement.
- Report briefly: what changed and whether the Alpha build/install succeeded.
  Leave visual verification to the owner; do not claim it was performed.

### Shield installs: direct ADB over Tailscale

- Build and install from the thread's machine. All three machines are on Tailscale
  with the owner's Shield (`100.105.135.30:5555`, `shield.taildbfaa7.ts.net`);
  the VPS route was verified on 2026-10-07. Transfer the APK straight to the
  Shield; there is no jump host.
- Keep Tailscale connected on all three machines and the Shield, and network
  debugging enabled on the Shield. If prompted, the owner approves that
  machine's ADB authorization on the Shield. For a requested install, the helper
  connects to the known target:

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
./scripts/build-alpha.sh --install 100.105.135.30:5555
```

- For an authorized Alpha install, build/install from the intended development
  branch with `./scripts/build-alpha.sh --install 100.105.135.30:5555`.
  If the intended Alpha APK is already built, install that APK directly instead:

```bash
adb -s 100.105.135.30:5555 install -r app/build/outputs/apk/weaselfin/alpha/WeaselPlex-alpha-arm64-v8a.apk
```

- Retain the shared Alpha debug signing key and use in-place installs to
  preserve Alpha login/settings. Do not uninstall or clear data to work around a
  signing mismatch. Installation success is sufficient; owner testing follows.
- If the direct connection fails, stop and report the problem. Do not fall back
  to the other machine. A paused install stays paused until the owner resumes it.

### Stable releases

Official publish = merge to `weaselfin` -> tag that merge commit -> signed APK from the tag. No pre-merge device check required.

Ship branch is **`weaselfin`** (not upstream `main`). All WeaselPlex release tags (`v1.1.x`, etc.) are on `weaselfin`. Do not publish from `main` unless the owner explicitly changes that policy.

Release signing: release builds need the WeaselFin signing keystore (same key as prior installs). Unsigned APKs will not upgrade over existing installs. Prefer building the signed release on a host that already has the keystore; do not commit keystore files or passwords.

versionCode: the `weaselfin` flavor takes it from the release tag (`v1.2.6` -> `10206`), so tag first, then build from the tag. `assembleWeaselfinRelease` refuses to run if the code is not above 74, the last release numbered by tag count. Android refuses an update whose versionCode is lower than the installed one ("App not installed"); v1.2.5 first shipped that way on 2026-10-01.

Before uploading, check the new APKs against the current Latest release, and stop if any check fails:

```bash
BT=~/Android/Sdk/build-tools/36.0.0
gh release download --repo Weaseltv/Wholphin -p WeaselFin-release-arm64-v8a.apk -D /tmp/latest-tv   # current Latest
$BT/aapt2 dump badging /tmp/latest-tv/WeaselFin-release-arm64-v8a.apk | grep -o "versionCode='[0-9]*'"
$BT/aapt2 dump badging <new>.apk | grep -o "versionCode='[0-9]*'"   # must be higher than Latest's
$BT/apksigner verify --print-certs <new>.apk | grep SHA-256          # must start 132905a2
unzip -l <new-arm64-v8a>.apk | grep libffmpegJNI.so                  # native playback extensions
```


## Distribution (WeaselPlex Android TV)

- **In-app updates:** GitHub Releases on `Weaseltv/Wholphin` (not `theweasel.tv` direct-distribution).
- **New installs (Downloader `9216225`):** `aftv.news/9216225` → `https://theweasel.tv/fin` → GitHub `.../releases/latest/download/WeaselFin-release.apk`.
  There is **no separate VPS APK copy** to refresh. Publishing the GitHub release (correct asset names + mark as Latest) updates both in-app updates and new Downloader installs.
- After tagging on `weaselfin`: upload signed assets named exactly `WeaselFin-release.apk` / `WeaselFin-release-arm64-v8a.apk` (and other ABI splits if built). Release **title/name** must be exactly `vX.Y.Z` (no `— WeaselPlex` suffix) or the in-app updater reports no update available.
- **Release notes are required.** The release body is what users read in the app: on the update page and beside "Install update" in Settings. Write it for them: a one-sentence summary, then one bullet per change, the way v1.2.5–v1.2.7 do. Never publish a release with an empty or placeholder body.
- Ship is not done until all of these are true:
  1. GitHub release `vX.Y.Z` exists with those asset names and is the repo **Latest** release.
  2. `curl -sI https://theweasel.tv/fin` redirects to that release's `WeaselFin-release.apk` (via `.../releases/latest/download/...`).
  3. In-app updater can see `vX.Y.Z` (plain title).
- Do not use the removed GitHub Actions Create release workflow; build/sign on VPS or ThinkCentre (with WholphinExtensions / ffmpeg packages wired).
- Do not tell the owner a "VPS website APK still needs refreshing" after a correct Latest GitHub publish — `/fin` already tracks Latest.

## Close builds when you finish: memory (owner, 2026-10-22)

The owner uses the ThinkCentre (15 GB RAM) while agents build on it. A finished build's idle Gradle and Kotlin daemons
kept about 6 GB for hours, and leftover `/tmp` files kept another 3 GB, starving the owner's other work. Follow these
rules on both machines, even between Alpha iterations:

- **When your build or test run finishes, close it.** Stop the daemons it left:
  `./gradlew --stop` and `pkill -u "$USER" -f KotlinCompileDaemon`. First check that no other agent is mid-build
  (`pgrep -af GradleWorkerMain`, or a Gradle `java` process using CPU in `top`), because `./gradlew --stop` also kills
  another agent's running build. If one is running, leave the daemons; the idle timeout below closes them.
- **Leave nothing running** when you report back: no Gradle daemons, emulators, dev servers, `adb logcat`, watchers or
  background shells you started.
- **`/tmp` is RAM on the ThinkCentre** (tmpfs). Put large scratch files (APK copies, bundles, test output, unpacked
  archives) under `~/work/` instead, and delete your own `/tmp` files before you finish.
- **Machine settings** in `~/.gradle/gradle.properties` (never commit that file; it holds a credential):
  `org.gradle.daemon.idletimeout=600000` (an idle Gradle daemon exits after 10 minutes instead of 3 hours) and
  `kotlin.compiler.execution.strategy=in-process` (no separate Kotlin daemon). Add them if missing.
- The Alpha exception below keeps build *files* on disk for incremental builds. It never covers running processes.

## VPS and ThinkCentre disk hygiene (added 2026-10-03)

Release and test builds leave gigabytes behind (`app/build`, Gradle outputs; 1–2 GB per TV build, about 6 GB per phone release). On 2026-10-03 the T3 VPS was down to 40 GB free from old release folders. Apply the Alpha exception first; otherwise, before you finish any task that built on either host, delete what you built:

- **Active Alpha iteration exception (2026-10-07):** retain `app/build`, root
  `build`, `wholphin-mpv-stub/build`, `.gradle` and `.kotlin` in the active Alpha
  worktree across task boundaries for incremental builds. Overwrite the current
  Alpha APK instead of keeping an archive per tweak. Remove throwaway copies and
  abandoned Alpha worktrees when the batch ends, the owner requests cleanup or
  disk pressure requires it. Never delete the host debug key or shared Gradle
  dependency cache. Check `df -h ~` after builds; report less than 30 GB free.

- **Release build folders, once the GitHub release is published and verified:** remove the worktree you built from (e.g. `git -C ~/work/weaselfin/Wholphin worktree remove --force <path>`, or delete `~/work/weaselfin/releases/tv-vX.Y.Z`). The tag keeps the source and the GitHub release keeps the APKs.
- **Everything else you built:** test builds, throwaway clones in `/tmp`, emulator test APKs. Also stop your build
  daemons (see "Close builds when you finish" above).
- Then run `df -h ~`, and say in your report if less than 30 GB is free.
