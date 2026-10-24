# Profile pictures (avatars): dropping in the real set

Kid-profiles plan 4.6. Every WeaselPlex profile (adults and kids) can pick a picture from a set
bundled in the app. The picture is saved as the profile's Jellyfin user image, so the phone apps
and the Web Player show it too.

## What ships now

The owner's final set (2026-10-22): **48 avatars**, 24 for kids and 24 for adults, 512 × 512 WebP
(quality 88) converted from the owner's 512 × 512 PNGs. About 920 KB of assets in total.

- A **kid profile** picks from `kids/` (add-kid flow, chooser "Change picture", Settings).
- An **adult profile** picks from `adults/`. A profile whose kind isn't known yet gets the kids set.

They live in `app/src/weaselfin/assets/avatars/<kids|adults>/<id>.webp`. The file name is the
avatar id and is never renamed; ids are unique across both sets. The app lists whatever `.webp`
files are in each folder (sorted by name), so adding or removing files needs no code change.

Kids (`kids/`): `bee-blue`, `bunny-pink`, `chick-ocean`, `crab-aqua`, `dinosaur-yellow`, `dragon-red`, `elephant-peach`, `fox-green`, `frog-magenta`, `hedgehog-seafoam`, `kitten-periwinkle`, `koala-lavender`, `lion-sky`, `monkey-indigo`, `octopus-lime`, `owlet-coral`, `panda-mint`, `penguin-orange`, `puppy-forest`, `robot-orchid`, `rocket-navy`, `turtle-cream`, `unicorn-purple`, `whale-rose`

Adults (`adults/`): `alien-black`, `astronaut-indigo`, `bear-forest`, `bulldog-lilac`, `cat-silver`, `cobra-turquoise`, `crow-tangerine`, `eagle-sapphire`, `gorilla-fuchsia`, `jaguar-mustard`, `knight-mauve`, `lynx-rose`, `moose-sky`, `ninja-sand`, `owl-plum`, `pirate-midnight`, `raccoon-olive`, `rhino-khaki`, `robot-rust`, `shark-teal`, `stag-denim`, `tiger-emerald`, `viking-sage`, `wolf-crimson`

The 8 `placeholder-*` avatars of the first Alphas were removed. A profile that picked one keeps
that picture: what is saved is the uploaded image on the media server, not the bundled file. Only
the picker's "current" mark is lost (the app remembered the placeholder id), and nothing else
reads the old ids.

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
   python3 scripts/avatars-to-webp.py kids ~/avatars-in/kids/*.png
   python3 scripts/avatars-to-webp.py adults ~/avatars-in/adults/*.png
   ```

   It writes `app/src/weaselfin/assets/avatars/<kids|adults>/<id>.webp` and refuses non-square,
   transparent or badly named files.
2. Remove any avatar being retired. Profiles that already picked it keep that image on the server;
   only the "current" mark in the picker is lost.
3. Build an Alpha and hand it to the owner.

## How saving works (for reference)

- The picker uploads the chosen avatar as PNG with the profile's own token
  (`ImageApi.postUserImage`, SDK 1.7.1) and removes it with `ImageApi.deleteUserImage`.
- The app remembers which avatar each profile picked on this TV (only to mark it in the picker)
  and bumps a per-profile cache key on the image URL after every save or remove, so Coil fetches
  the new picture.
- Phase 0 must confirm on the 12.1 server that a non-admin member and a kid can set their own
  image.
