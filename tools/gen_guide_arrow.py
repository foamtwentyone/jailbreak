"""Cellbreak 0.5.13: the routine arrow as pixel art, like the vanilla compass: 32 pre-drawn directions
(no blurry rotated pixels), 24x24 each, in an 8x4 atlas. Frame i points i * 360/32 degrees clockwise
from straight up.

Usage: python3 gen_guide_arrow.py <assets_dir>
"""
import math
import os
import sys

from PIL import Image

N = 24
FRAMES = 32
# Arrow outline in its own space: y up is "forward", units of pixels around the centre.
SHAPE = [(0, 10.5), (7.5, 2.5), (3, 2.5), (3, -9.5), (-3, -9.5), (-3, 2.5), (-7.5, 2.5)]
LIGHT = (255, 236, 140)
MID = (250, 196, 46)
DARK = (196, 128, 18)
OUTLINE = (44, 30, 8)


def inside(poly, x, y):
    c = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            c = not c
        j = i
    return c


def frame(deg):
    a = math.radians(deg)
    ca, sa = math.cos(a), math.sin(a)
    fill = {}
    for py in range(N):
        for px in range(N):
            # Pixel centre in arrow space (screen y is down): rotate back by the frame angle.
            x = px + 0.5 - N / 2
            y = N / 2 - (py + 0.5)
            ax = x * ca - y * sa
            ay = x * sa + y * ca
            if inside(SHAPE, ax, ay):
                fill[(px, py)] = MID
    # A bevel lit from the upper left, as on vanilla items (fixed on screen, whatever the direction).
    for (px, py) in list(fill):
        if (px - 1, py - 1) not in fill or (px, py - 1) not in fill and (px - 1, py) not in fill:
            fill[(px, py)] = LIGHT
        elif (px + 1, py + 1) not in fill or (px, py + 1) not in fill or (px + 1, py) not in fill:
            fill[(px, py)] = DARK
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    for (px, py), col in fill.items():
        img.putpixel((px, py), col + (255,))
    for py in range(N):
        for px in range(N):
            if (px, py) in fill:
                continue
            if any((px + dx, py + dy) in fill for dx in (-1, 0, 1) for dy in (-1, 0, 1)):
                img.putpixel((px, py), OUTLINE + (235,))
    return img


def main(root):
    atlas = Image.new("RGBA", (N * 8, N * 4), (0, 0, 0, 0))
    for i in range(FRAMES):
        atlas.paste(frame(i * 360 / FRAMES), ((i % 8) * N, (i // 8) * N))
    out = os.path.join(root, "textures", "gui")
    os.makedirs(out, exist_ok=True)
    atlas.save(os.path.join(out, "guide_arrow.png"))


if __name__ == "__main__":
    main(sys.argv[1])
