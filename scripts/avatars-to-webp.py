#!/usr/bin/env python3
"""Convert the owner's avatar PNGs to the WebP files the weaselfin flavor bundles.

Usage: scripts/avatars-to-webp.py <png> [<png> ...]

Each file name (without .png) becomes the avatar id, so it must already be lowercase-hyphen
(e.g. red-fox.png -> red-fox.webp). Writes to app/src/weaselfin/assets/avatars/ and refuses
files that break the spec in runbooks/kid-profiles/avatars.md. Needs Pillow with WebP support.
"""

import os
import re
import sys

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "weaselfin", "assets", "avatars")
ID = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")


def convert(path: str) -> str | None:
    name = os.path.splitext(os.path.basename(path))[0]
    if not ID.match(name):
        return f"{path}: name must be lowercase-hyphen (a-z, 0-9, -)"
    with Image.open(path) as im:
        if im.size[0] != im.size[1]:
            return f"{path}: not square ({im.size[0]}x{im.size[1]})"
        if im.mode in ("RGBA", "LA") and im.getchannel("A").getextrema()[0] < 255:
            return f"{path}: has transparency; avatars need an opaque background"
        rgb = im.convert("RGB")
        if rgb.size != (512, 512):
            rgb = rgb.resize((512, 512), Image.LANCZOS)
        rgb.save(os.path.join(OUT, f"{name}.webp"), "WEBP", quality=88, method=6)
    return None


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    os.makedirs(OUT, exist_ok=True)
    errors = [e for e in (convert(p) for p in sys.argv[1:]) if e]
    for e in errors:
        print(e, file=sys.stderr)
    print(f"Converted {len(sys.argv) - 1 - len(errors)} avatar(s) into {os.path.normpath(OUT)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
