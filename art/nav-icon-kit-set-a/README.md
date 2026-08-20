# WeaselFin TV Navigation Icon Kit - Set A

Final Android vector drawable handoff based on the selected **Cinematic Solid** concept family.

## Integration

Copy the twelve XML files from `drawable/` into:

`app/src/main/res/drawable/`

The app can tint every icon independently at runtime. Each drawable uses an opaque white fill as the neutral source color.

## Production Contract

- Android vector drawable XML only.
- Canvas: `android:viewportWidth="960"` and `android:viewportHeight="960"`.
- Display size: `android:width="24dp"` and `android:height="24dp"`.
- Color: opaque `android:fillColor="#FFFFFF"` on every path.
- Filled geometry only; no strokes, gradients, opacity, clip paths, or transformed groups.
- Artwork stays inside the central safe area with consistent TV-distance optical weight.

## Files

| Drawable | Navigation item | Visual metaphor |
| --- | --- | --- |
| `ic_nav_search.xml` | Search | Bold magnifier |
| `ic_nav_home.xml` | Home | Solid house |
| `ic_nav_favorites.xml` | Favorites | Solid heart |
| `ic_nav_requests.xml` | Discover / Requests | Magnifier with plus |
| `ic_nav_movies.xml` | Movies | Clapperboard with play cutout |
| `ic_nav_tvshows.xml` | TV Shows | Antenna television with episode tiles |
| `ic_nav_standup.xml` | Stand Up Comedy | Stage microphone and platform |
| `ic_nav_ufc.xml` | UFC | Fist inside an octagonal cage |
| `ic_nav_boxing.xml` | Boxing | Crossed boxing gloves |
| `ic_nav_4k_movies.xml` | 4K Movies (LAN) | Clapperboard, resolution pixels, and wired nodes |
| `ic_nav_4k_tv.xml` | 4K TV Shows (LAN) | Antenna television, resolution pixels, and wired nodes |
| `ic_nav_settings.xml` | Settings | Filled gear |

`set-a-icon-kit-preview.png` is the enlarged proof sheet. `set-a-24dp-readability-proof.png` renders the vectors at their actual nominal pixel size. `manifest.json` records path counts and rendered alpha bounds.

The source project resources were not modified. This directory is a reviewable handoff kit for the app agent.
