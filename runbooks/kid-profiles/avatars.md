# Profile pictures (avatars): dropping in the real set

Kid-profiles plan 4.6. Every WeaselPlex profile (adults and kids) can pick a picture from a set
bundled in the app. The picture is saved as the profile's Jellyfin user image, so the phone apps
and the Web Player show it too.

## What ships now

Alpha builds carry **8 placeholder avatars** (simple drawn animals on solid colours, made by an
agent, no characters or text) so the flow can be tested:

`placeholder-bear`, `placeholder-bunny`, `placeholder-cat`, `placeholder-chick`,
`placeholder-fish`, `placeholder-fox`, `placeholder-owl`, `placeholder-turtle`

They live in `app/src/weaselfin/assets/avatars/<id>.webp`. The file name is the avatar id. The
app lists whatever `.webp` files are in that folder (sorted by name), so adding or removing files
needs no code change.

## What the owner supplies

24–48 images in one consistent style that you hold the rights to (no TV or movie characters):

- Square, **512 × 512 px**, **PNG**, sRGB.
- Opaque, full-bleed background (no transparency).
- Subject inside the centre ~80% circle: the phone app and Web Player may crop to a circle. The
  TV tile is a 120 dp square and the nav-drawer icon is 24 dp (about 48 px), so it must still
  read at 48 px.
- No text, borders or rounded corners.
- Under ~300 KB each.
- **File names are the ids**: lowercase letters, digits and hyphens (`red-fox.png`). They never
  change once shipped.

Send them to the agent (Taildrop to the build machine is fine).

## What the agent does

1. Convert and check them (Pillow with WebP is on the ThinkCentre):

   ```bash
   python3 scripts/avatars-to-webp.py ~/avatars-in/*.png
   ```

   It writes `app/src/weaselfin/assets/avatars/<id>.webp` and refuses non-square, transparent or
   badly named files.
2. Delete the `placeholder-*.webp` files once the real set is in. Profiles that already picked a
   placeholder keep that image on the server; only the "current" mark in the picker is lost.
3. Build an Alpha and hand it to the owner.

## How saving works (for reference)

- The picker uploads the chosen avatar as PNG with the profile's own token
  (`ImageApi.postUserImage`, SDK 1.7.1) and removes it with `ImageApi.deleteUserImage`.
- The app remembers which avatar each profile picked on this TV (only to mark it in the picker)
  and bumps a per-profile cache key on the image URL after every save or remove, so Coil fetches
  the new picture.
- Phase 0 must confirm on the 12.1 server that a non-admin member and a kid can set their own
  image.
