#!/usr/bin/env python3
"""Install the deterministic Hyper Beam icon in SMEDIT's item sprite sheets.

Requires Pillow. The ROM pickup itself is generated separately as indexed SNES
4bpp graphics by ``build_hyper_beam_item_patch.py``; this icon is the matching
editor/map representation at sprite-sheet cell (80, 80).
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

from build_hyper_beam_item_patch import ROOT, build_hyper_frame


ICON_X = 80
ICON_Y = 80
SHEETS = (
    ROOT / "shared" / "src" / "jvmMain" / "resources" / "item_sprites.png",
    ROOT / "desktopApp" / "src" / "jvmMain" / "resources" / "item_sprites.png",
)

# Transparent, orb shadow, orb interior, shell, beam halo, beam core.
COLOURS = {
    0: (0, 0, 0, 0),
    1: (5, 6, 18, 255),
    9: (18, 22, 48, 255),
    12: (54, 48, 102, 255),
    13: (32, 206, 255, 255),
    15: (238, 255, 255, 255),
}


def build_icon() -> Image.Image:
    icon = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    frame = build_hyper_frame(pulse=False)
    for y, row in enumerate(frame):
        for x, colour_index in enumerate(row):
            icon.putpixel((x, y), COLOURS[colour_index])
    return icon


def main() -> None:
    icon = build_icon()
    original_bytes = [path.read_bytes() for path in SHEETS]
    if len(set(original_bytes)) != 1:
        raise SystemExit("SMEDIT item sprite sheets differ; refusing to update only one copy")

    for path in SHEETS:
        sheet = Image.open(path).convert("RGBA")
        if sheet.size != (128, 128):
            raise SystemExit(f"Unexpected item sprite sheet size for {path}: {sheet.size}")
        current = sheet.crop((ICON_X, ICON_Y, ICON_X + 16, ICON_Y + 16))
        if current.getbbox() is not None and current.tobytes() != icon.tobytes():
            raise SystemExit(
                f"Item sprite cell ({ICON_X}, {ICON_Y}) is already occupied in {path}"
            )
        sheet.alpha_composite(icon, (ICON_X, ICON_Y))
        sheet.save(path, format="PNG", optimize=True)
        print(f"Updated {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
