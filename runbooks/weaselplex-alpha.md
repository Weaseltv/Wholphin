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
| Card height / between cards | 172 dp / 14 dp |
| Start / end padding | 4 dp / 16 dp |
| Base / extra vertical padding | 4 dp / 12 dp |
| Divider-to-padding / after-row gap | 12 dp / 8 dp |
| Title-to-divider gap | 3 dp |
| Row title size / letter spacing | 30 sp / 1.8 sp (0.06 em) |
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
400 dp cap. Their existing height and line limits remain. Row titles align
with the first poster's normal left edge using the row's start padding,
independently of focus enlargement or border width. Title position stays
stable while navigating; full-width dividers and right-side counts stay in place.
Settings uses a 5 dp horizontal divider with the Home rule's 10 dp / 70% glow,
and a 5 dp vertical edge without glow. Section titles are 22 sp bold with extra
space before controls. Controls have 32 dp panel edge padding. Preference sliders
keep white text on a black background when focused, with a white outlined track,
white fill and black unfilled section. Toggle colors follow the section accent
(Ice White in Settings). The Customize home page, Customize Navigation Drawer
Items and More user profile settings entries omit their explanatory subtitles.

## Live layout and card appearance tuner (Alpha only)

Open **Settings → Customize home page → Live layout tuner (Alpha)**. Layout
controls are restored alongside the card appearance controls.
Select a Home row and choose whether edits apply to that row or all rows.
Up/down selects a control; left/right adjusts the displayed value live.

Layout controls include card height and spacing, vertical padding, row side/end
padding, row and divider gaps, title text size and letter spacing (0.1 sp steps),
row-count text size/opacity/right padding, and divider thickness/glow. Edits can
apply to the selected Home row or all Home rows; these do not replace packaged
app-wide defaults or change Library grids.

Focus controls include border thickness (0–8 dp), border opacity, glow spread
(0–48 dp), glow strength, focus scale (100–120%), card corner radius (0–32 dp),
and border/glow color (row accent, Volt, Orange, White, Cyan or Pink).

Poster season/episode/collection-count badge controls include text size (8–40 sp),
text and background opacity, horizontal/vertical inset (0–64 dp), horizontal/vertical
inner padding (0–32 dp) and corner rounding (0–50%). Opacity uses 0–100%.
Unset badge values use the approved defaults: 11 sp text, 8 dp edge insets
and 4 dp inner padding. Badge controls apply to all Home rows and supply the
app-wide poster-overlay appearance through the normal saved Home settings.
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

- Poster captions share a 14 sp title with up to two lines and ellipsis; short titles use one line without reserving an empty second line. Subtitles also ellipsize, and missing subtitles do not reserve a blank line. Series year labels use the production year, falling back to the premiere-date year when available; when both are missing, no year is invented. The text stays bounded instead of using a marquee. MOVIE/SHOW badge font size and padding follow global poster-count appearance.

- Sidebar palette order revision 5 places Stand Up Comedy before Sports for new installs and migrates existing local pins/server library order once. Recommended rows inherit the live library Home card appearance instead of overriding it with default row options.

- Home layout revision 6 packages the approved focus/count appearance for all WeaselPlex rows: 2 dp border, 80% opacity (100% for providers/Picks), 15 dp glow at 70%, 109% focused scale, and 11 sp counts with 75% backgrounds, 5 dp insets, 2 dp inner padding and 20% corners. Existing untouched legacy appearance fields migrate once locally and in server display preferences; non-default user tuning and later overrides are retained. This batch remains Alpha until the owner explicitly requests stable publication.

### Next Alpha batch after v1.2.27 (2026-10-08)

Poster focus border opacity is now 100% app-wide. Approved Home layout revision 7 applies this once to every existing local/server Home row and packages it for new installs. This is the new default for existing and new users when the next stable release is published from Alpha; later personal adjustments remain overrides. Border thickness, glow strength/colors and count appearance remain unchanged.

Library tabs now use outlined buttons with the normal section focus styling. Watchlist type buttons and library filter toolbars align with poster cards; toolbar buttons use the same 16 dp spacing as the type buttons. Episode poster counter badges use compact season/episode labels such as S1E4, with episode-only fallback when season metadata is absent. Series/season title counts remain counts.

Search displays Movies, TV Shows, Episodes and People switches horizontally below the search bar. Their defaults are enabled; Collections and Requests remain under View options → Include types and default to disabled. Explicit saved search choices remain overrides. The Include types dialog has a larger heading, the standard section divider thickness/glow and internal clearance around focused rows.

The prior grid-glow fix reserves 32 dp internal top padding. This batch is Alpha only until the owner explicitly requests another stable release.

### Alpha background-color crash fix (2026-10-08)

Owner-requested direct Shield ADB diagnostics captured a main-thread crash at 00:37:52 while entering TV Shows Recommended: `IllegalArgumentException: Invalid ID, must be in the range [0..16)`, through Android `ColorSpace.get` / `Paint.setColor` and Compose `BackgroundNode.drawRect`. Background states in focusable loading/empty rows passed `Color.Unspecified`, whose packed color-space ID is 16, to the drawable background and color animation. Replace that sentinel with explicit `Color.Transparent`. Apply the same correction to overview/value text, metadata rows, rating backgrounds and the non-neon slider background, while retaining unspecified text/icon tint semantics. No settings migration or visual styling changes are required.

Build and install the arm64 Alpha in place; the owner checks the navigation that previously crashed. Do not submit the crash report or navigate the Shield remotely.

### Movies dual-color palette (2026-10-08)

Movies now use Molten `#FF5200` for borders, icons, labels, controls and solid section/progress lines, with Safety Orange `#FF7900` for glow. Shared Neon glow helpers resolve the glow color separately from the solid accent, covering card focus, outline/primary buttons, list/sidebar focus, sidebar tally and icon halos, section rules, badges and playback seek/progress glow. Existing thickness, opacity, spread and focus scale remain.

The palette follows Movies library chrome, Recommended/Library/Collections/Genres/Studios, Home movie rows and mixed-row movie cards, Watchlist posters and Movies filters, Search and Discover movie posters, movie details and inherited dialogs/playback. Mixed pages resolve movie cards/details to Movies rather than inheriting utility-page colors. Stand Up Comedy/Sports library identities and provider/curated collection palettes retain their own colors. These shared code defaults apply to existing and new users without a settings migration; customer publication remains a separate explicit release request.

### Section border/glow palettes (2026-10-08)

The following owner-approved pairs supersede the prior section accent colors throughout WeaselPlex. Borders, section accents, solid rules, labels/icons and control fills use the border color; focus glow, title-rule glow, sidebar halos/tallies and playback progress glow use the separate glow color. Shared helpers retain existing geometry, opacity, strength and scale.

| Section | Border / solid accent | Glow |
| --- | --- | --- |
| Search | Midnight Volt `#0A2CFF` | Glacier Glow `#00A3FF` |
| Watchlist | Deep Cyan `#00B8D9` | Electric Cyan `#00E1FF` |
| Requests | True Teal `#009A93` | Neon Teal `#00F2D6` |
| Home | Forest Neon `#0B8A42` | Matrix Green `#00E04B` |
| Collections | Taxi Neon `#FFD300` | Lemon Glow `#FFFF33` |
| Movies | Molten `#FF5200` | Safety Orange `#FF7900` |
| TV Shows | Laser Crimson `#E8112D` | Electric Red `#FF1A1A` |
| Stand Up Comedy | Royal Neon `#5A00FF` | Grape Glow `#9B30FF` |
| Sports | Electric Rose `#FF0099` | Blush Volt `#FF85D0` |

`NeonSectionPalette` is the single shared mapping for those section pairs. Navigation and library/type resolution apply it across Home, Watchlist, Search/Discover, library tabs, nested details and controls. Movie and show cards in mixed pages use their media palettes; known Stand Up Comedy and Sports libraries keep their own identities. Provider, curated and sports-collection poster palettes retain their earlier exact values. Generic Live TV/status/error tokens stay separate from the section palette. These packaged values apply to existing and new users on the next owner-authorized stable release; this build remains Alpha.

Requests now uses True Teal `#009A93` for its sidebar and page accents, with Neon Teal `#00F2D6` for shared focus, halo and divider glows. Nested Requests controls inherit this pair; movie/show posters retain their media-type palettes. Existing appearance settings remain in place.

### Sidebar active labels and content padding (2026-10-08)

Remove the selected-page stripe from the sidebar. When focus moves to another menu item, the active page's label keeps its section border color without text glow. Its selected icon glow remains. Expanded sidebar icons, labels and supporting text gain 8 dp left inset within the existing focus border; border geometry and collapsed icon alignment stay fixed. Applies to all sidebar entries, including the profile button's shared padding.

### Search filter alignment (2026-10-08)

The inline Movies, TV Shows, Episodes and People row balances the page's unequal rail/right gutters with an extra 16 dp on the right, giving equal 40 dp clearance from the collapsed sidebar and right screen edge. Each label/switch pair is centered within its equal-width button. Top row padding drops from 20 dp to 4 dp, reducing the search-bar-to-filter gap from 32 dp to 16 dp; lower focus-glow clearance and 16 dp between buttons remain.

### TV Shows tab alignment and Recommended row clearance (2026-10-08)

TV Shows tab buttons start at 8 dp, aligned with the sidebar mascot, instead of 36 dp. Remove the 16 dp outer top inset and reduce the tab row's internal top inset from 20 dp to 8 dp, retaining the lower focus-glow room. TV Recommended header top padding drops from 27 dp to 4 dp because the tabs already supply the screen-top clearance. Together these reclaim 51 dp for the first poster row and its focused border/glow; the header's 172 dp content height, card dimensions and appearance tuning remain.

The same 8 dp tab-button top alignment now applies to Movies, Stand Up Comedy and Sports through their shared movie-library page. These buttons move up 28 dp, with lower tab glow padding retained.

Movies, Stand Up Comedy and Sports also use the compact Recommended header, via the shared `HeaderUtils.recommendedModifier()`. Its 4 dp top inset reclaims another 23 dp beneath the tabs so the first row's posters and focus effects have the same room as TV Shows. TV Shows uses the same helper to keep these four pages in parity. Home retains its original header spacing; card size and appearance settings remain.

### Global row-title alignment (2026-10-08)

Row headings align with the first un-enlarged card edge throughout WeaselPlex. Shared ItemRow resolves title start padding from its actual card content padding, covering Home, Recommended, details and Search rows. Remove Home's focus-scale/border offset calculation. Standalone Discover and chapter rows use their card gutters; Search grid headings use the grid's zero horizontal inset. People rows already share the same 8 dp heading/card inset. Title position does not move when focus changes; card positions, full-width dividers and row counts remain.

### Media-library button-row spacing (2026-10-08)

Movies, TV Shows, Stand Up Comedy and Sports retain the 8 dp tab-button top alignment with the sidebar mascot. Reduce the tab-to-toolbar gap from 36 dp to 16 dp, matching Watchlist's type-button-to-toolbar spacing. Keep the shared 48 dp toolbar-to-first-card clearance (16 dp toolbar bottom padding plus 32 dp inside the grid), matching Watchlist and retaining room for enlarged poster borders and glow. Tab focus glow uses the remaining 16 dp internal lower inset; other tabbed pages retain their prior spacing.


### Watchlist heading and global badge alignment (2026-10-08)

Watchlist uses the Collections page-title component above its type buttons and toolbar, with a WATCHLIST title and full-width section-colored divider. Counts and MOVIE/SHOW labels share a vertically centered overlay row, including when a favorite heart is present. Both sides use the global count edge inset, and version-count badges also follow count appearance. Approved Home layout revision 8 sets 8 dp top/side insets and 4 dp inner padding on all four sides for existing local/server rows and new users once; later user adjustments remain available. Episode/series counts and media-type badge sizing use the same global appearance throughout the app. This change remains Alpha until a stable release is explicitly authorized.

### Watchlist hearts at bottom right (2026-10-08)

Favorite/watchlist hearts sit at the bottom right of shared poster and banner cards throughout the app. Right and bottom clearance use the same global badge horizontal/vertical insets as MOVIE/SHOW overlays (8 dp by default), keeping each card path's existing heart size and color. Media-type and count badges stay in their aligned top row. This shared placement is standard for existing and new users when the next stable release is promoted from Alpha; no settings migration is needed for placement.


### Orbitron / Exo 2 typography and bold-line sidebar icons (2026-10-08)

Replace Barlow with bundled official Google Fonts static Orbitron ExtraBold 800 for display/page/section headers, with approximately 0.08 em tracking, and Exo 2 400/500/600/700 for all other app text. Both TV Material and standard Material typography use these families throughout WeaselPlex, including other color themes, cards, forms, dialogs, settings and playback UI. Sidebar labels use Medium 500; profile name uses Semibold 600 and its subtitle Regular 400. Compact clocks/numerals use Exo 2. Layout revision 9 updates existing local/server Home heading tracking once at each saved heading size and supplies 2.4 sp tracking for new 30 sp headings. Header line height accommodates Orbitron rather than the old condensed metrics. Preserve font resource-shrinker keep rules for release builds and bundle each family's OFL attribution in About libraries; retire Barlow assets.

Create a coordinated 24 dp rounded bold-outline vector set for Search, Home, Watchlist, Requests, Collections, Movies, TV Shows, Stand Up Comedy (microphone), Sports (trophy), and Settings. Collections/movie/TV library fallbacks also resolve the new vectors. Sidebar icon ink uses each section's supplied glow color even when inactive; selected icon halos retain existing glow behavior. Settings uses Frost White #EAF2FF. Keep existing section border/glow palettes and sidebar order. This is Alpha only; existing and new users receive the packaged fonts/icons with the next owner-authorized stable release. Owner performs visual verification.

## Audiowide display headings and split Home rows (2026-10-09)

Audiowide Regular 400 replaces Orbitron for shared display typography throughout
WeaselPlex. Display tracking is 0.06 em; existing heading sizes stay the same.
Exo 2 remains the body/sidebar font. Both fonts ship offline with OFL attribution.
Approved layout revision 10 changes saved Home title tracking to 0.06 em once;
subsequent live tuning remains available in Alpha.

Home (including its Settings and tuner previews) splits semantic row headings:
combined playback uses NEXT UP & above CONTINUE WATCHING, Streaming Services and
The Weasel’s Picks use COLLECTIONS, and Recently Added/Released rows show that
label above the source library name. Custom/unresolved rows retain their title.
Recommended and collection-detail rows keep their existing heading structure.
Kickers use Exo 2 SemiBold 600 at half the title size, all caps, 0.18 em tracking,
and the row glow color. Main titles are white Audiowide; row counts are Exo 2
SemiBold in #787E8C. Divider thickness/glow and card layout values are preserved.
The existing measured title-height scroll clearance includes the kicker.

Sidebar labels stay Exo 2 Medium 500 at normal tracking with #E1E4EC inactive
text; the active-page section color and focused appearance remain. Profile name
uses #F0F2F8 and the server subtitle #828A9B. Sidebar icons remain unchanged.

Recently Added Home rows use **NEW IN** directly, including Movies, TV Shows,
Stand Up Comedy and Sports, without depending on their palette classification.
Recently Released kickers remain unchanged.

Green section-title rules use Home’s Matrix Green #00E04B, matching the green
kicker text. The shared section-rule helper retains configured thickness, glow
and opacity and leaves the other section palettes unchanged.

## Watchlist / Movies palette swap (2026-10-09)

Watchlist now uses Molten #FF5200 borders/solid accents and Safety Orange #FF7900
glow. Movies now uses Deep Cyan #00B8D9 borders/solid accents and Electric Cyan
#00E1FF glow. This supersedes their earlier palette assignments above.
Shared rail, page, library/type, media card, button, badge, divider and playback
helpers consume the swapped section palettes. Direct movie references to the
literal Orange token now resolve Movies instead; the theme's movie tertiary and
preview follow Movies too. Watchlist movie cards and its Movies filter follow
Movies cyan, while native Watchlist chrome is orange. Other media/provider/pick
palettes and configured appearance dimensions remain. These packaged colors
apply to existing/new users without a preference reset; routine delivery is Alpha.

## Locked global presentation and Picks Home order (2026-10-09)

Owner accepted the latest live tuning from all eight Alpha Home rows. Sanitized
full capture: `runbooks/home-layout-defaults-2026-10-09.json`. Revision 11 applies
the approved presentation once to existing local/imported/server Home settings;
new rows and shared renderers use the same compiled defaults. Publication to
customer builds still requires an explicit stable release request.

| Setting | New approved global default |
| --- | --- |
| Portrait card height / between cards | 170 dp / 10 dp |
| Base / extra vertical padding | 4 dp / 4 dp |
| Start / end padding | 4 dp / 16 dp |
| Divider-to-card / after-row gap | 12 dp / 8 dp |
| Title size / tracking / divider gap | 35 sp / 1.8 sp / 1 dp |
| Row count size / opacity / right padding | 14 sp / 100% / 15 dp |
| Divider thickness / glow / strength | 3 dp / 10 dp / 80% |
| Poster focus border / opacity | 2 dp / 100% |
| Focus glow / strength / scale | 10 dp / 85% / 108% |
| Badge text / background / inset / inner padding | 11 sp / 75% / 8 dp / 4 dp |

Shared section/title/rule, poster/collection/badge defaults, responsive grid gaps,
Recommended rows, detail/Search/cast/chapter rows, and button/list/sidebar focus
recipes consume these values beyond Home. Responsive grids and specialist card
aspect ratios remain responsive; semantic image/fit/title settings are preserved
by the saved-settings migration. Alpha's tuner remains available for later local
experiments; revision 11 is applied once rather than every startup.

The Weasel’s Picks is now the last Home row, beneath Sports in the current layout.
The Home resolver relocates the existing row without duplicating it or replacing
its settings. Streaming stays near Continue Watching. Sidebar ordering is untouched.

## Home next-row heading clearance (2026-10-09)

Home now caps its detail-header space using the available viewport height, the
measured focused row and the next nonempty row's complete heading (kicker, title
and rule), with additional bottom clearance. This fixes partially cut-off next
headings after the approved title/card sizing changes across Continue Watching,
Streaming Services, Movies, TV Shows, Stand Up Comedy and Sports. The same layout
calculation is used in the Home previews. It preserves saved tuning and ordering;
Recommended pages retain their own header layout. Delivery is arm64 Alpha via
direct VPS ADB; visual verification belongs to the owner.
