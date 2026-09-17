#!/usr/bin/env python3
"""Rebuild the WeaselPlex Android TV banner (ic_banner_art) in the reflection style.

The layout copies the owner's Projectivy tile set: the lockup at 80% of the width with
its baseline on the vertical midline, a soft drop shadow, and a slightly blurred mirror
image below that starts at 28% opacity and fades out over 90% of the lockup height.
The background stays transparent so the tile floats over the launcher wallpaper.

Run from the repo root (needs Pillow):  python3 art/neon-board-b/make_reflection_banner.py
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageFilter

RES = Path("app/src/weaselfin/res")
LOCKUP = RES / "drawable-nodpi/weaselplex_lockup.png"
DENSITIES = {
    "mdpi": (320, 180),
    "hdpi": (480, 270),
    "xhdpi": (640, 360),
    "xxhdpi": (960, 540),
    "xxxhdpi": (1280, 720),
}
W, H = 1280, 720


def build() -> Image.Image:
    logo = Image.open(LOCKUP).convert("RGBA")
    logo = logo.crop(logo.getbbox())
    lw = int(W * 0.80)
    lh = round(logo.height * lw / logo.width)
    logo = logo.resize((lw, lh), Image.LANCZOS)
    x, base = (W - lw) // 2, H // 2
    y = base - lh

    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))

    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ink = Image.new("RGBA", logo.size, (0, 0, 0, 255))
    ink.putalpha(logo.getchannel("A").point(lambda v: int(v * 0.55)))
    shadow.alpha_composite(ink, (x + 4, y + 6))
    out.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(5)))

    mirror = logo.transpose(Image.FLIP_TOP_BOTTOM).filter(ImageFilter.GaussianBlur(1.2))
    fade_h = int(lh * 0.9)
    fade = Image.new("L", (1, lh), 0)
    for i in range(lh):
        t = max(0.0, 1 - i / fade_h)
        fade.putpixel((0, i), int(255 * 0.28 * t**1.3))
    mirror.putalpha(ImageChops.multiply(mirror.getchannel("A"), fade.resize((lw, lh))))
    out.alpha_composite(mirror, (x, base + 4))

    out.alpha_composite(logo, (x, y))
    return out


def main() -> None:
    master = build()
    for density, size in DENSITIES.items():
        image = master if size == master.size else master.resize(size, Image.LANCZOS)
        image.save(RES / f"mipmap-{density}/ic_banner_art.png", optimize=True)
        print(density, size)


if __name__ == "__main__":
    main()
