# WeaselPlex Alpha launcher artwork

Generated with the built-in image-generation tool, using the existing WeaselPlex
launcher foreground and TV banner as edit targets. These masters preserve
transparency. Their resized Android resources live exclusively under
`app/src/alpha/res`, at mdpi through xxxhdpi; the customer flavor's artwork is
unchanged. The icon's transparent padding keeps its mascot and ALPHA badge inside
the adaptive icon mask. Alpha's adaptive icon omits the customer monochrome layer
so it cannot show the unbadged customer mascot as its themed icon.

- `icon-foreground.png`: square mascot with a neon lime ALPHA badge.
- `tv-banner.png`: WeaselPlex wordmark with an ALPHA badge below PLEX.

## Final prompts

### Icon

```text
Use case: precise-object-edit. Asset type: Android adaptive launcher icon foreground for WeaselPlex Alpha. Edit target: the supplied existing square WeaselPlex mascot icon. Keep the original mascot identity, pose, neon lime highlights, white/charcoal fur, circular play button, graphic style and centered composition extremely close to the reference. Add one bold, clearly legible badge reading exactly "ALPHA" in uppercase (A L P H A), directly below the mascot. Badge should be neon lime with dark charcoal lettering, simple bold condensed sans-serif, visible at tiny launcher icon sizes. Keep the mascot and badge together entirely inside the central 66 percent of the square canvas so an Android circular or squircle mask will not clip either. Preserve the transparent background and generous outer transparent padding of the edit target. Output one square icon asset, no phone mockup, no extra words, no watermark.
```

### TV banner

```text
Use case: precise-object-edit. Asset type: Android TV 16:9 launcher banner for WeaselPlex Alpha on NVIDIA Shield. Edit target: the supplied existing WeaselPlex banner. Keep the original mascot and play button, exact WEASELPLEX wordmark, neon lime and white colors, transparent background, graphic style, wide layout and reflected artwork extremely close to the reference. Add a prominent badge reading exactly "ALPHA" in uppercase (A L P H A), neon lime with dark charcoal bold condensed sans-serif lettering. Place the ALPHA badge in the available space just below the main wordmark and above its reflection, aligned beneath the PLEX portion so it is highly visible in the Shield Apps row. Preserve the 16:9 canvas and original logo scale and padding; preserve genuine transparency around all artwork. Output only the launcher banner asset, no mockup, no extra text or watermark.
```
