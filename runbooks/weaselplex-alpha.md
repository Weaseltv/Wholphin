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

## Approved Home layout defaults

The owner-approved settings were read from Alpha on 2026-10-07. The sanitized
snapshot of all eight rows is `runbooks/home-layout-defaults-2026-10-07.json`;
no user IDs, library IDs, queries or login data are included.

| Setting | Approved default |
| --- | --- |
| Card height / between cards | 172 dp / 16 dp |
| Start / end padding | 4 dp / 16 dp |
| Base / extra vertical padding | 4 dp / 12 dp |
| Divider-to-padding / after-row gap | 12 dp / 8 dp |
| Title-to-divider gap | 3 dp |
| Row title size / letter spacing | 30 sp / 0 sp |
| Row count size / opacity / right padding | 14 sp / 100% / 15 dp |
| Divider thickness / glow spread / strength | 5 dp / 10 dp / 70% |
| Card shape / captions | Portrait 2:3 / off |
| Image fit | Streaming: Fit; other rows: Fill |

Streaming's saved episode shape remains 16:9; other episode shapes are 2:3.
Image-source selections remain separate from layout defaults.

New WeaselPlex installs and newly created rows use these defaults. Existing local
or imported layouts receive the approved layout once. Revision 2 changes only
between-card spacing to 16 dp for rows already on revision 1, preserving all other
layout and card appearance tuning. The original capture above records the prior
20 dp spacing. Row names, queries, order and image-source options are preserved.
The 16 dp spacing is also saved once to each signed-in member's Alpha and customer
server-backed Home preferences on their next load; failed writes retry later.
Other display preferences are retained. Later user changes are saved with
the current revision and remain overrides;
the approved values are never reapplied at every startup. This code will reach
customer installs when the owner explicitly publishes a stable release.

## Streaming Services provider focus colors

Streaming Services focus borders/glow use the owner's provider palette, keeping
the existing border thickness, glow tuning and row divider color. Revision 3
sets only this row's focus border opacity to 100% once for existing settings;
new Streaming rows use the same default. Other rows are unchanged and later
explicit user adjustments remain overrides:


| Provider | Hex |
| --- | --- |
| Netflix | #E50914 |
| Disney+ | #113CCF |
| Hulu | #1CE783 |
| Max (HBO) | #002BE7 |
| Prime Video | #00A8E1 |
| Paramount+ | #0064FF |
| Peacock | #FDB927 |
| Apple TV | #A2AAAD |
| AMC+ | #00EEE6 |
| MGM+ | #EFBE73 |
| STARZ | #F5E000 |
| BritBox | #66D3EB |
| Crunchyroll | #FF5E00 |
| Hallmark+ | #613790 |
| Angel | #E8B923 |

Border-only contrast overrides use Apple TV's #A2AAAD for Paramount+ and
Hallmark, and #000000 for BritBox and Crunchyroll. Their glow retains the provider
color listed above; other provider borders and glow remain matched.

## The Weasel’s Picks focus colors

Picks Home posters use their theme color for both focused border and glow,
retaining saved border opacity/thickness and glow spread/strength. Unknown card
names retain the row accent.

| Pick | Hex |
| --- | --- |
| Blockbuster Friday | #2567F6 |
| Clear Your Evening | #FFA91E |
| Cozy Season | #40C7FF |
| Critics Circle | #F4AA3D |
| Crowd Pleasers | #FF1438 |
| Date Night | #FF40A2 |
| Hot Right Now | #FE3B04 |
| Just Dropped | #FFDF0F |
| Lights Off | #FC1132 |
| Originals Only | #F6C93E |
| Passport Night | #0CD8CA |
| Quick Fix | #35FCAD |
| Ripped From The Headlines | #FF080B |
| Spin The Wheel | #AA32FF |
| Spooky Season | #FF6F00 |
| The Big Score | #40D4FF |
| VHS Vault | #FF0AD5 |
| Y2K Rewind | #406AFF |

## Home descriptions and Settings styling

Home descriptions use 92% of the available header width instead of the previous
400 dp cap. Their existing height and line limits remain. Home row titles align
with the first poster's focused left edge, calculated from the saved card size,
shape, focus enlargement, border thickness and start padding. Title position stays
stable while navigating; full-width dividers and right-side counts stay in place.
Settings uses a 5 dp horizontal divider with the Home rule's 10 dp / 70% glow,
and a 5 dp vertical edge without glow. Section titles are 22 sp bold with extra
space before controls. Controls have 32 dp panel edge padding. Preference sliders
keep white text on a black background when focused, with a white outlined track,
white fill and black unfilled section. Toggle colors follow the section accent
(Ice White in Settings). The Customize home page, Customize Navigation Drawer
Items and More user profile settings entries omit their explanatory subtitles.

## Live card appearance tuner (Alpha only)

Open **Settings → Customize home page → Live layout tuner (Alpha)**. The completed
layout controls have been removed from this overlay; saved layout values remain.
Select a Home row and choose whether edits apply to that row or all rows.
Up/down selects a control; left/right adjusts the displayed value live.

Focus controls include border thickness (0–8 dp), border opacity, glow spread
(0–48 dp), glow strength, focus scale (100–120%), card corner radius (0–32 dp),
and border/glow color (row accent, Volt, Orange, White, Cyan or Pink).

Poster season/episode/collection-count badge controls include text size (8–40 sp),
text and background opacity, right/top inset (0–64 dp), horizontal/vertical
inner padding (0–32 dp) and corner rounding (0–50%). Opacity uses 0–100%.
Untuned badges keep their existing sizing, including Picks' badge scaling.
These controls change presentation, not the count or episode label itself.

Use **Hide controls / inspect Home** to focus cards and see their border/glow;
**Back** brings the overlay back. **Save & close** (or Back from the controls)
saves normal local per-user Home settings. **Discard this tuning session**
restores values from when the tool opened. The tuner entry is hidden from
customer builds. Normal Home row customization remains available.

## Neon palette v4 and sidebar order

Owner palette and order, 2026-10-07:

| Sidebar item | Color | Hex |
| --- | --- | --- |
| User | Volt | #C6FF00 |
| Search | Electric Blue | #0088FF |
| Home | Neon Green | #3DFF6E |
| Watchlist | Cyan | #00E5FF |
| Requests | Teal | #00C2A0 |
| Collections | Bright Yellow | #FFE600 |
| Movies | Amber | #FFB000 |
| TV Shows | Crimson | #FF2A3D |
| Sports | Fuchsia | #FF1A8C |
| Stand Up Comedy | Violet | #7B4DFF |
| Settings | Ice White | #E8F4FF |

Sidebar items use this order rather than server library order. Existing users'
order is restored once, retaining pinned/hidden choices; later manual ordering
is preserved. Additional libraries remain accessible after the named sections.
On the next signed-in load, a separate one-time migration also saves Collections,
Movies, TV Shows, Sports and Stand Up Comedy in that order to the member's
Jellyfin `OrderedViews`. It fetches fresh configuration, preserves unrelated
preferences and remaining view IDs, and retries failed writes on a later load.

Poster watch-progress lines keep their existing 3 dp thickness and use the same
renderer as the Home divider. The Home divider's 10 dp / 70% gradient includes
its 5 dp solid line, leaving a visible 5 dp fade peaking at 35% alpha. Progress
uses an 8 dp / 56% gradient including its 3 dp line to match that visible fade. Their color follows the
media type (Amber for movies, Crimson for episodes), independently of the current
section. The curated Home row is now named **The Weasel’s Picks**. HBO Max
uses the normal focus border without a permanent unfocused outline. Continue
Watching keeps its Neon Green divider while focused posters use the item’s library
accent: Amber for Movies, Crimson for TV Shows, Fuchsia for Sports and Violet
for Stand Up Comedy. Resolve actual library ancestry for resumed items, caching
it by user and parent/series; use media-type colors if lookup fails. Other rows retain their row
accent for focus borders and glow.

Section accents follow the navigation stack into details, grids, dialogs,
settings and playback, including full-screen destinations. Home rows use their
source library's mapped color. Explicit poster border color overrides still work.
Both TV and Material theme accents inherit the current section.

## Sidebar focus border

Sidebar buttons match the owner's poster border tuning captured on 2026-10-07:
2 dp border at 80% opacity, 15 dp glow spread at 70% strength, square corners,
and each button's section accent. Rail glow room includes half the border width
so all four edges fit. This applies to profile, built-in and library buttons.
Poster focus enlargement and poster badge settings remain Home-card controls.

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

### Streaming provider Home headers

Streaming Services cards show “Streaming on [provider] right now.” rather than the server collection overview. Each provider has a bundled transparent wordmark on the right of the Home header, while the title and item count remain on the left. Home clears collection photo backdrops for these cards. Artwork sources are recorded in `provider-wordmark-sources.md`.

### Collections page styling and ordering

The Collections library header aligns its title and LIBRARY eyebrow with the first poster. It adds 27dp top padding, a 20dp divider-to-toolbar gap, and another 16dp below the toolbar before the grid's existing padding. The title rule reads the Picks Home divider thickness/glow values, in Collections yellow.

Collection grid cards share the current user's Picks Home focus/count settings. Picks and the 29 additional collections use their supplied palettes; Streaming Services use the same provider styles and contrasting border overrides as Home. Every Collections focus border is fully opaque. Sports collection borders and glows use Boxing #F29E19, UFC Fight Night #3B8BF2, UFC on ABC #1FF2D4, UFC on ESPN #F22519 and UFC PPV #FECD30. Titles beneath all Collections grid posters are hidden; other library poster titles retain their existing settings. Layout defaults revision 4 updates existing Picks Home border opacity to 100%, preserving other settings and later user tuning.

The COLLECTION type label is removed from the Collections grid; title counts remain. The Alpha tuner retains focus-border and count controls and removes the obsolete COLLECTION label controls.

Name ordering loads the complete member-scoped collection list and follows the explicit owner-supplied sequence in `CollectionDisplayOrder`: all 47 named themed collections (including The Big Score under T), then Boxing/UFC A–Z, then the 15 providers in Home order. Matching ignores punctuation/case and recognizes provider naming aliases. A one-time per-user library migration selects Name ascending for existing saved sorts. Collections queries retain BOX_SET when the UI type filter is unset; otherwise filter application clears the type and bypasses the ordered-list loader. Unrecognized collections follow those groups. Alphabet jumps use the displayed list rather than server SortName counts. Explicit alternative sorts and filters remain available.

BritBox's provider SVG uses an expanded, normalized view box so the i dot and letter bottoms are fully included.

### Media-card parity (2026-10-07)

- Watchlist resolves actual library membership for per-item Movie, TV Show, Sports and Stand Up Comedy focus colors. Library pages share their saved Recently Added Home row focus treatment.
- Numeric poster badges use the saved Home count appearance app-wide; poster badge controls update all rows.
- Pure Movies/TV Shows library grids hide type badges; mixed-media grids retain them. The filter toolbar has the same 16 dp lower inset as Collections.
- Home layout revision 5 updates saved local and server-default row spacing to 14 dp; WeaselPlex poster grids also use 14 dp.

- Poster captions share a 14 sp, two-line title with ellipsis and fixed title-line allocation; subtitles also ellipsize. The text stays bounded instead of using a marquee. MOVIE/SHOW badge font size and padding follow global poster-count appearance.
