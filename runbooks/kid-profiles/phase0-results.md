# Kid profiles — Phase 0 results (server proof)

Run: 2026-10-21, track P0 (round 2), API level against the live WeaselPlex server, with owner
approval for one test kid. The owner checks the Shield separately.
Test kid: `Kid Test P0`, Jellyfin id `13dccdb7feb04e6e853b0243efb9e7dd`, Seerr user id 27. Both
deleted at the end (see "Cleanup"). No other user, setting, plugin, service or deploy was changed.

TV prerequisite: the Seerr-guard Alpha is PR #67 (`t3/kid-p0-seerr-guard`). It was built but not
installed.

## Summary

| Gate | Result | One line |
|---|---|---|
| 0. Versions / ratings / member policy | Recorded | **Jellyfin 12.2.0** (not 12.1), Seerr 3.5.0. Score table matches plan 1.1. Every user has `MaxParentalRating` null or absent. |
| 1. Level table | **PASS after a change** | `MaxParentalSubRating` must be **1**, not null. With null, Teens hides all TV-MA and NC-17. |
| 2. Above-level item denied | **PASS** on every API surface, with 3 by-id caveats | Images and static streams are served without auth to anyone who knows an item id. The HLS master playlist is issued to the kid. |
| 3. Unrated / box sets / episodes / extras | **PASS** | Unrated items are hidden at every level (audit below). Box sets show only allowed children. Episodes follow the series rating and their own stricter rating. |
| 4. Tightening applies on the next request | **PASS** | Same token, next request: every surface denied. |
| 5. Seerr | **PASS** (deny proven) | Kid imported with permissions 0 can sign in but gets 403 on movie and series requests. Without import, a kid would be auto-created with 160 (REQUEST + AUTO_APPROVE). |
| 6. Quick Connect / SSO plugin | **PASS** | Quick Connect works for the kid. The SSO plugin did not change the kid's policy. Password API login still works although the plugin has `DisablePasswordLogin=true`. |
| Avatars | **PASS** | The kid sets, reads and deletes its own image with its own token. BlockUnratedItems does not hide it. Member self-image not tested (no test member). |
| 7. Cleanup | **PASS** | Kid, its Seerr user, its two test devices and its tokens are gone. User counts are back to 25 (Jellyfin) and 26 (Seerr). |

## Gate 0 — server facts

- `/System/Info`: **Jellyfin Server 12.2.0**. Plugins include Community SSO 5.0.0.0 (1 OIDC
  provider, 0 SAML, 0 provisioning profiles, `DisablePasswordLogin: true`). Quick Connect is enabled.
- Seerr: 3.5.0 (`ghcr.io/seerr-team/seerr:latest`).
- Users: 25 total, 23 enabled non-admins. `MaxParentalRating` is null on all 25. None has "kid" in
  its name. All use `DefaultAuthenticationProvider`.
- Member A, read-only via admin `/Users`: `MaxParentalRating` **absent** (reads as null),
  `MaxParentalSubRating` null, `BlockUnratedItems` [], 5 enabled folders, Live TV off,
  `IsHidden` true. Adults therefore satisfy the TV kid signal (`maxParentalRating == null`).
- `/Localization/ParentalRatings` (score/subScore), US entries:

| Rating | Score/sub | Rating | Score/sub |
|---|---|---|---|
| Approved, G, TV-G, TV-Y | 0/0 | PG-13 | 13/0 |
| TV-Y7 | 7/0 | TV-14 | 14/0 |
| TV-Y7-FV | 7/1 | TV-14-D/L/S/V and every combination | 14/1 |
| PG, TV-PG | 10/0 | R | 17/0 |
| TV-PG-D/L/S/V and every combination | 10/1 | NC-17, TV-MA, TV-MA-L/S/V and combinations | 17/1 |
| TV-X, TV-AO | 18/0 | 21 / XXX / Banned | 21 / 1000 / 1001 |

  "Unrated" has no score. The server also maps many non-US strings (see gate 1).

## Gate 1 — level table

Test kid policy: the website's member overlay (`weaselPlexMemberPolicy` and
`weaselPlexMemberConfiguration`, with the five folders resolved by name exactly as the website
does: Movies, TV Shows, Stand Up Comedy, Sports and Collections), plus the contract 1.1 kid
fields. Read-back verified every field: `MaxParentalRating`, `MaxParentalSubRating`,
`BlockUnratedItems` (8 types), `IsAdministrator=false`, `IsDisabled=false`, `IsHidden=true`,
Live TV and Live TV management off, `SyncPlayAccess=None`, content deletion off,
`EnableSharedDeviceControl=true`, 5 folders, and OrderedViews in member order.

Method: an admin inventory of Movies and Series in the four member libraries (11,781 items:
9,381 movies and 2,400 series), compared with what the kid token lists, with `CollapseBoxSetItems=false`.

| Policy | Kid sees | US ratings result | Unexpected |
|---|---|---|---|
| 10 / null | 2,084 | G, PG, TV-G, TV-PG, TV-Y, TV-Y7 and Approved all visible. PG-13 0/1,961, TV-14 0/686 | none missing |
| 14 / null | 4,801 | + PG-13 1,961/1,961, TV-14 686/686. R 0/3,606, TV-MA 0/1,367 | none missing |
| 17 / null | 8,502 | R 3,606/3,606, **TV-MA 0/1,367, NC-17 0/15** | **1,382 should-be-visible titles hidden** |
| **17 / 1** | 9,882 | R all, **NC-17 15/15, TV-MA 1,365/1,367**. 18, 18+, M18 and R21 hidden | 2 TV-MA series hidden (below) |

- **Finding: on 12.2.0 a null `MaxParentalSubRating` behaves like 0.** Items at the ceiling
  score with subScore 1 are hidden. The plan and contract assume null admits every sub-score.
  Teens needs sub-rating 1 to admit TV-MA and NC-17. The same rule would hide TV-PG-x (10/1)
  at Kids and TV-14-x (14/1) at Older kids. The library has **no** TV-Y7-FV, TV-PG-x, TV-14-x,
  TV-MA-x, TV-X or TV-AO item today (checked on movies, series and episodes), so those rows
  could not be tested with real items. No rating on the server has a subScore above 1.
- **Confirmed table 1.1 for 12.2.0:** Kids `10/1`, Older kids `14/1`, Teens `17/1`.
- Gate tests: Kids admits TV-PG (no TV-PG-x item exists) and rejects PG-13 (0/1,961). Older kids
  admits TV-14 (no TV-14-x item exists) and rejects R (0/3,606). Teens (17/1) admits R and TV-MA
  and rejects score 18 (18 0/14, 18+ 0/8, M18 0/22). TV-X and TV-AO do not exist in the library.
- **NC-17 titles: 15** (all movies). All are visible to Teens at 17/1, as the owner accepted.
- Two TV-MA series stay hidden even at 17/1: "DAHMER - Monster: The Jeffrey Dahmer Story" and
  "Top Boy". Their stored inherited rating probably does not match the displayed one; a metadata
  refresh should fix it. This fails closed.
- Non-US ratings the server scores at 10 or below are admitted at Kids (24 titles), for example
  L (Bake Squad, Meet the Chimps), U, TP, ALL (Marvel's 616), A (Gary Busey: Pet Judge), E
  (Alien Xmas), 0/6/7/10 (The Mole, Inside the CIA: Secrets and Spies), and **B (Chimp Crazy)**.
  The owner should review B and the numeric ratings, or fix their metadata.
- Non-US strings rated 12, 13+, 14, 14+, PG13 and N-14 are admitted at Older kids. 15, 16, NC16,
  MA15+, MA 15+, B-15, SAM 16, Från 15 år and MA-17 are admitted at Teens.

## Gate 2 — above-level item denied (and gate 4)

Items: the newest R movie (`da74a8bd…`) and a TV-MA series (`0e09f68d…`). Their episodes carry
no rating of their own. At Teens (17/1) the kid set a resume point on the movie and marked
episode 1 played. All surfaces then showed the items (200, present). The policy was then
tightened to Kids (10/1). Every call below used the **same token** on the next request.

| Surface | At Kids |
|---|---|
| `/Items` list (SearchTerm; Movies newest 50) | 200, absent |
| `/Search/Hints` | 200, absent |
| `/Items/Latest` | 200, absent |
| `/UserItems/Resume` | 200, absent (it was present at Teens) |
| `/Shows/NextUp` | 200, absent (it was present at Teens) |
| `/Items/{id}/Similar` | 404. Similar of 5 visible PG movies: 53 results, 0 above level or unrated |
| `GET /Items/{id}?userId=` | 404 |
| `GET /Users/{kid}/Items/{id}` | 404 |
| Episode `GET /Items/{ep}`; `/Shows/{id}/Episodes` | 404; 404 |
| `/Items/{id}/Images/Primary` with kid token | 404 |
| `POST /Items/{id}/PlaybackInfo` (movie and episode) | 404 |
| `/Items/{id}/Download` | 404 |
| **`/Items/{id}/Images/Primary` without auth** | **200** (any caller who knows the id) |
| **`/Videos/{id}/stream?static=true` with kid token, and without auth** | **206** (1-byte range read only) |
| **`/Videos/{id}/master.m3u8` with kid token** | **200** playlist (without auth: 401). Segments were not fetched, to avoid starting a transcode |

Gate 4 is **PASS**: the tightened policy applied on the very next request with the existing
token. No re-login was needed.

The three caveats are stock Jellyfin behaviour and affect every user, not just kids: images and
static streams are served by item id. A kid cannot find above-level ids through any listing,
search or detail call above, so the exposure needs an id from outside (a shared link, Seerr, a
parent's screen). This is recorded for the owner to decide on, not as a blocker. No server
change was made.

## Gate 3 — unrated, box sets, episodes, extras

Unrated audit (Movies and Series). All of these are hidden at every level, Teens 17/1 included:

| Library | Total | Missing | NR | Not Rated | Unrated | Other unrecognised |
|---|---|---|---|---|---|---|
| Movies | 8,827 | 733 | 501 | 106 | 21 | 10 |
| TV Shows | 2,400 | 121 | 9 | 0 | 0 | 57 |
| Stand Up Comedy | 317 | 92 | 16 | 3 | 0 | 0 |
| Sports | 237 | 212 | 14 | 4 | 0 | 0 |

- "Other unrecognised" strings the server cannot score (hidden like unrated): Passed 3, M 3,
  GP 3, M/PG 2, C 1, 19 1, Genel İzleyici 1. Approved is scored (0) and visible at every level.
- Examples of unrated titles kids will not see: Movies, *Black Wax*, *RSVP*, *Michael Bublé's
  Christmas in the City*. TV Shows, *All or Nothing: Manchester City*, *The Dictator's
  Playbook*. Stand Up, *Dave Chappelle: Killin' Them Softly*, *Tom Segura: Completely Normal*.
  **Sports is 95% unrated** (226/237, mostly UFC events), so kids effectively get no Sports. The
  owner decides whether to fix or accept this (expected for UFC).
- **Box sets:** 684 sets in Collections. 665 are visible at Teens, and 290 of those mix allowed and
  above-level or unrated children. At Kids all 290 stay visible. Their child lists contain only
  policy-allowed children: 273 match the US-rated allowed set exactly, and the other 17 differ
  only by the non-US ratings already admitted in library browse. **0 above-level children leaked.**
  No visible set is empty. Example: "Trending Movies" (3 allowed, 26 hidden) shows the kid 3
  children and ChildCount 3, and its overview names no hidden title. **Owner visual check on the
  Shield:** set artwork (Primary/Thumb) is per-set, not per-user, so a generated or downloaded
  poster may show above-level art.
- **Episodes:** episodes with no rating of their own follow the series (a TV-MA series' episodes
  return 404). Episodes with their own stricter rating inside a lower-rated series are hidden
  (2 TV-14 episodes in TV-PG series, e.g. *Once Upon a Time*, return 404). Three visible TV-PG
  series show all their episodes to the kid (139/139, 76/76, 133/133).
- **Extras/trailers:** no PG or G movie has local trailers or special features, so the allowed case
  could not be tested. For a hidden R movie, LocalTrailers and SpecialFeatures return 404. Trailer
  items visible in browse: 0.

## Gate 5 — Seerr

Read-only settings (`settings.json` on unraid; API key not printed):
- `mediaServerType` 2 (Jellyfin), `mediaServerLogin: true`, `newPlexLogin: true` (new
  media-server users may sign in), `localLogin: false`.
- **`defaultPermissions: 160` = REQUEST (32) + AUTO_APPROVE (128).** Quotas 0 (unlimited).
- **No background auto-import.** The new kid was not in Seerr: 0 matches among 26 users, and it
  was listed as importable. A Seerr user is created only on that user's first Seerr sign-in, or
  by an admin import, and either way gets 160.

Deny mechanism (plan 3.5 default), proven:
1. Admin `POST /api/v1/user/import-from-jellyfin` with the kid created Seerr user 27 with
   **permissions 160**. `POST /user/27/settings/permissions {permissions: 0}` then gave 0.
2. From the web host to `10.42.0.2:5055`, `POST /api/v1/auth/jellyfin` as the kid returned 200,
   user 27, permissions 0.
3. `POST /api/v1/request` for a movie returned **403** "You do not have permission to make movie
   requests". For a series it returned **403** "...series requests". No request was created
   (0 before deleting the user).
4. Permissions 0 still lets the kid browse Discover (`/discover/movies` 200).

## Gate 6 — Quick Connect and the SSO plugin

- `POST /QuickConnect/Initiate` (unauthenticated) returned 200. Admin
  `POST /QuickConnect/Authorize?code=&userId=<kid>` returned 200.
  `POST /Users/AuthenticateWithQuickConnect` returned 200, and the token's `/Users/Me` is the kid
  (MaxParentalRating 10, sub 1).
- The policy hash was identical before and after the Quick Connect sign-in and after the
  password sign-in. The SSO plugin only provisions or rewrites users on its own OIDC flow, and
  the kid never uses that flow.
- **Finding:** the plugin has `DisablePasswordLogin: true`, but `POST /Users/AuthenticateByName`
  with the kid's password still returned 200, and so did Seerr's Jellyfin password sign-in. The
  flag does not stop API password logins.

## Avatars

With its own token at Kids (BlockUnratedItems on): `POST /UserImage?userId=<kid>` (PNG) returned
204 and set `PrimaryImageTag`. `GET /UserImage` and `/Users/{id}/Images/Primary` returned 200
image/png. `DELETE /UserImage` returned 204, then the tag was null and GET returned 404. The user
image is readable without auth (normal Jellyfin). Hidden users are not in `/Users/Public`. The
member self-image check was skipped because no test member exists.

## Cleanup

- Seerr: user 27 had 0 requests. `DELETE /api/v1/user/27` returned 200, then GET returned 404.
  0 users match the kid's Jellyfin id. Total users back to 26.
- Jellyfin: deleted only the two test devices (`kidp0-kid-dev`, `kidp0-qc-dev`, 204 each). Shared
  devices (for example Seerr's) were not touched. `DELETE /Users/<kid>` returned 204, then GET
  returned 404. Users back to 25, none named Kid Test P0, none with a MaxParentalRating. Both kid
  tokens return 401. 0 devices or sessions reference the kid.
- Work files removed: `~/kidp0work` and `/dev/shm/kidp0r` on the web host, the temp id file on
  unraid, and `/tmp/kidp0` locally.

## Plan and contract adjustments

1. **Contract 1.1 / plan 1.1 (orchestrator):** server is **12.2.0**. Set
   `MaxParentalSubRating = 1` for all three levels (Kids 10/1, Older kids 14/1, Teens 17/1). The
   sentence "Sub-rating must be null, not 0" is wrong on 12.2.0, where null behaves as 0. Wire
   ids are unchanged. Website read-back must verify sub-rating 1.
2. **Seerr (plan 3.5):** keep "import via admin API, then permissions 0". Because sign-in
   auto-creates with 160 including AUTO_APPROVE, kid creation must **fail closed** if the
   import or permission write fails: disable the kid, or don't hand it to the TV. Also add a
   reconcile that re-imports any kid missing from Seerr and re-applies 0. The import briefly
   writes 160 before the 0; that window is acceptable because the kid has no session yet.
   - Server-side alternative (production change, owner decision only): set `defaultPermissions`
     to 0 and have the website grant REQUEST to members explicitly. This removes the
     auto-create risk entirely, but changes how every new member is set up.
3. **Kid credentials:** create kids with a random password of 32+ characters that is never shown,
   as for members. `DisablePasswordLogin` does not block API password logins, and Seerr password
   sign-in works too, so a known kid password would reach Seerr. Quick Connect stays the only
   sign-in path.
4. **TV:** the Seerr guard (PR #67) stays required. Seerr permission 0 still allows Discover
   browsing, so the TV must keep Discover and Requests hidden for kids, as the Alpha does.
5. **Owner decisions (no change made):** anonymous image and static-stream access by id; a
   metadata refresh for the 2 stale TV-MA series; review of the non-US ratings admitted at Kids
   (notably B and the numeric ones); Sports being 95% unrated; box-set artwork on the Shield.
6. Phase 0 cannot test TV-PG-x, TV-14-x, TV-X or TV-AO with real items, because none are in the
   library. The sub-rating rule above comes from the TV-MA and NC-17 (17/1) evidence.
