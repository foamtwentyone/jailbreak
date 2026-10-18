"""Cellbreak 0.5.5: the guard dogs as German shepherds (wolf model, 64x32 texture).

Usage: python3 gen_shepherd.py <assets_dir>

Box UV layout of the vanilla wolf model: for a box at (u, v) of size w x h x d the top face is at
(u+d, v), the bottom at (u+d+w, v), and the sides run along the row below: right (u, v+d), front
(u+d, v+d), left (u+d+w, v+d), back (u+2d+w, v+d).
"""
import os
import random
import sys

from PIL import Image

TAN = (178, 122, 58)
TAN_L = (204, 150, 80)
BLACK = (34, 30, 28)
DARK = (70, 52, 36)

img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
px = img.load()
rnd = random.Random(7)


def put(x, y, c):
    k = rnd.choice((0.9, 0.96, 1.0, 1.0, 1.05))
    px[x, y] = tuple(max(0, min(255, int(v * k))) for v in c) + (255,)


def rect(x0, y0, w, h, c):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            put(x, y, c)


def box(u, v, w, h, d, top, bottom, right, front, left, back):
    rect(u + d, v, w, d, top)
    rect(u + d + w, v, w, d, bottom)
    for (x0, ww, c) in ((u, d, right), (u + d, w, front), (u + d + w, d, left), (u + 2 * d + w, w, back)):
        if callable(c):
            for y in range(h):
                for x in range(ww):
                    put(x0 + x, v + d + y, c(x, y, ww, h))
        else:
            rect(x0, v + d, ww, h, c)


def saddle_side(dark_near_start):
    """A body side: black along the edge that meets the back, tan towards the belly."""
    def f(x, y, w, h):
        edge = x if dark_near_start else w - 1 - x
        return BLACK if edge < w * 0.55 else TAN
    return f


# Head (0,0) 6x6x4: tan face, darker crown, black muzzle band under the eyes.
def face(x, y, w, h):
    if y < 2:
        return DARK
    if y == 2 and x in (1, 4):
        return (240, 220, 160) if x == 1 else (240, 220, 160)
    return TAN
box(0, 0, 6, 6, 4, DARK, TAN_L, TAN, face, TAN, DARK)
px[5, 6] = (20, 18, 16, 255)
px[8, 6] = (20, 18, 16, 255)
# Snout (0,10) 3x3x4: black.
box(0, 10, 3, 3, 4, BLACK, DARK, BLACK, BLACK, BLACK, BLACK)
# Ears (16,14) 2x2x1: black.
box(16, 14, 2, 2, 1, BLACK, BLACK, BLACK, BLACK, BLACK, BLACK)
# Body (18,14) 6x9x6 (turned to lie along the dog): the back is black (the saddle), the belly tan.
box(18, 14, 6, 9, 6, BLACK, TAN, saddle_side(False), BLACK, saddle_side(True), TAN_L)
# Mane / shoulders (21,0) 8x6x7: black on the back, tan chest.
box(21, 0, 8, 6, 7, BLACK, TAN, saddle_side(False), BLACK, saddle_side(True), TAN)
# Legs (0,18) 2x8x2: tan, darker at the top.
legs = lambda x, y, w, h: DARK if y < 2 else TAN
box(0, 18, 2, 8, 2, DARK, TAN, legs, legs, legs, legs)
# Tail (9,18) 2x8x2: black on top, tan underneath, dark tip.
tail = lambda x, y, w, h: BLACK if y > 5 or x == 0 else DARK
box(9, 18, 2, 8, 2, BLACK, TAN, tail, tail, tail, TAN)

root = sys.argv[1]
os.makedirs(os.path.join(root, "textures", "entity"), exist_ok=True)
img.save(os.path.join(root, "textures", "entity", "shepherd.png"))
