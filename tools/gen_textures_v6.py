"""Cellbreak textures, 0.3.11: richer items, blocks and skins.

Usage: python3 gen_textures_v6.py <assets_dir>
Redraws every texture except the work pickaxe and the work axe. Skins use the outer layer
(hats, hair, open jumpsuits, vests) the same way the 0.3.8 guards do.
"""
import math
import os
import random
import sys

from PIL import Image

import gen_textures as g

OUT = (28, 24, 28)


def clamp(v):
    return max(0, min(255, int(v)))


def sh(c, k):
    return tuple(clamp(v * k) for v in c[:3])


def mixc(a, b, t):
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3))


# ---------------------------------------------------------------- items

def item(cells, outline=OUT, outline_of=None):
    """16x16 item from {(x, y): rgb}; empty pixels touching the shape become a dark outline
    (a darker shade of the neighbour when `outline_of` is given)."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for (x, y), c in cells.items():
        if 0 <= x < 16 and 0 <= y < 16:
            px[x, y] = tuple(c[:3]) + (c[3] if len(c) == 4 else 255,)
    for y in range(16):
        for x in range(16):
            if (x, y) in cells:
                continue
            near = [cells[(x + dx, y + dy)] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + dx, y + dy) in cells]
            if near:
                px[x, y] = (sh(near[0], 0.28) if outline_of else outline) + (255,)
    return img


def diag(t, off=0):
    """A point on the bottom-left to top-right diagonal."""
    return 2 + t + off, 13 - t


def wood(t, side):
    base = (150, 104, 60) if side == 0 else (112, 74, 40)
    return sh(base, 1.0 + 0.06 * math.sin(t * 1.7))


def handle(cells, length, wrap=None, wrap2=None, start=0):
    """Two-pixel wooden handle along the diagonal; `wrap` rows get tape/string colours."""
    for t in range(start, length):
        x, y = diag(t)
        a, b = wood(t, 0), wood(t, 1)
        if wrap and t in wrap:
            a, b = wrap[t]
        cells[(x, y)] = a
        cells[(x + 1, y)] = b


def makeshift_pickaxe():
    cells = {}
    string = ((222, 208, 170), (176, 160, 120))
    handle(cells, 9, wrap={1: ((60, 60, 66), (40, 40, 46)), 2: ((60, 60, 66), (40, 40, 46)), 7: string, 8: string})
    # A head bent from a scrap of sheet metal: uneven, a few rust spots.
    rnd = random.Random(11)
    cx, cy = 3.0, 13.0
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - cx, cy - (y + 0.5)
            r = math.hypot(dx, dy)
            ang = math.degrees(math.atan2(dy, dx))
            wob = 0.35 * math.sin(ang / 9)
            if 8.4 + wob <= r <= 10.8 + wob and -10 <= ang <= 100:
                lit = r > 9.9 + wob
                c = (196, 198, 204) if lit else (140, 142, 150)
                if ang < 0 or ang > 90:
                    c = (104, 104, 112)
                if rnd.random() < 0.14:
                    c = (162, 96, 54) if rnd.random() < 0.6 else (124, 70, 40)
                cells[(x, y)] = c
    return item(cells)


def makeshift_shovel():
    cells = {}
    string = ((222, 208, 170), (176, 160, 120))
    handle(cells, 9, wrap={1: ((60, 60, 66), (40, 40, 46)), 7: string, 8: string})
    # A tin plate blade in the top-right corner, lit from the top-left.
    cxs, cys = 11.6, 3.6
    for y in range(16):
        for x in range(16):
            u = (x + 0.5 - cxs + (y + 0.5 - cys)) / math.sqrt(2)
            v = (x + 0.5 - cxs - (y + 0.5 - cys)) / math.sqrt(2)
            if (u / 2.4) ** 2 + (v / 3.4) ** 2 <= 1.0 and (x, y) not in cells:
                k = 1.18 - 0.12 * (u + 2.4) + 0.04 * v
                c = sh((176, 180, 188), k)
                if (x + y) % 5 == 0:
                    c = sh(c, 0.9)
                cells[(x, y)] = c
    cells[(13, 1)] = (236, 238, 242)
    cells[(12, 1)] = (220, 222, 228)
    return item(cells)


def file_tool():
    cells = {}
    cloth = ((86, 98, 120), (60, 70, 90))
    handle(cells, 5, wrap={0: cloth, 1: cloth, 2: cloth, 3: ((200, 200, 196), (160, 160, 156)), 4: cloth})
    for t in range(5, 12):
        x, y = diag(t)
        cells[(x, y)] = (196, 200, 210) if t % 2 else (150, 154, 164)  # teeth catch the light
        cells[(x + 1, y)] = (112, 116, 128) if t % 2 else (84, 88, 100)
    cells[diag(12)] = (226, 230, 236)
    return item(cells)


def shiv():
    cells = {}
    tape = ((236, 236, 230), (190, 190, 186))
    black = ((46, 46, 52), (30, 30, 36))
    handle(cells, 6, wrap={0: black, 1: tape, 2: black, 3: black, 4: tape, 5: black})
    # A shard of metal, ground to a point; a bright edge on one side.
    for t in range(6, 13):
        x, y = diag(t)
        w = 2 if t < 11 else 1
        cells[(x, y)] = (238, 240, 246)
        if w == 2:
            cells[(x + 1, y)] = (150, 158, 172)
    cells[(diag(6)[0] - 1, diag(6)[1])] = (120, 124, 134)  # guard
    cells[(diag(5)[0] + 2, diag(5)[1])] = (120, 124, 134)
    return item(cells)


def baton():
    """A black tonfa: a thick shaft with a lit edge and a side grip near the bottom."""
    cells = {}
    for t in range(0, 12):
        x, y = diag(t)
        cells[(x, y)] = (92, 92, 104) if t % 3 else (112, 112, 124)
        cells[(x + 1, y)] = (48, 48, 56)
        cells[(x + 1, y + 1)] = (30, 30, 36)
    cells[(2, 13)] = (24, 24, 28)
    cells[(3, 14)] = (24, 24, 28)
    for k, c in enumerate(((66, 66, 76), (66, 66, 76), (58, 58, 66))):
        cells[(4 - k, 9 - k)] = c
        cells[(5 - k, 9 - k)] = sh(c, 0.7)
    cells[(1, 6)] = (26, 26, 30)
    cells[(2, 6)] = (26, 26, 30)
    return item(cells)


def handcuffs():
    cells = {}
    steel_l, steel, steel_d = (226, 230, 236), (168, 172, 182), (108, 112, 124)
    for cx, cy in ((4.5, 9.5), (11.5, 9.5)):
        for y in range(16):
            for x in range(16):
                r = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if 2.2 <= r <= 3.6:
                    a = math.atan2(y + 0.5 - cy, x + 0.5 - cx)
                    cells[(x, y)] = steel_l if a < -1.6 else (steel_d if a > 1.0 else steel)
    for x, y in ((7, 6), (8, 5), (9, 6)):
        cells[(x, y)] = steel_d  # chain
    cells[(8, 6)] = steel
    return item(cells)


def cell_key():
    cells = {}
    clay, clay_l, clay_d = (172, 140, 112), (204, 176, 146), (128, 100, 78)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x + 0.5 - 5, y + 0.5 - 5)
            if 1.6 <= r <= 3.5:
                cells[(x, y)] = clay_l if x + y < 9 else (clay_d if x + y > 11 else clay)
    for t in range(0, 7):
        x, y = 7 + t, 7 + t
        cells[(x, y)] = clay_l
        cells[(x + 1, y)] = clay_d
    for x, y in ((10, 12), (11, 13), (12, 11), (13, 12)):
        cells[(x, y)] = clay
    cells[(9, 9)] = (150, 150, 158)  # the iron nugget pressed inside
    return item(cells)


def chalk():
    cells = {}
    for t in range(3, 11):
        x, y = diag(t)
        cells[(x, y)] = (248, 248, 244)
        cells[(x + 1, y)] = (214, 214, 206)
        cells[(x, y + 1)] = (232, 232, 226)
    cells[diag(11)] = (252, 252, 250)
    for p in ((3, 13), (5, 14), (2, 12)):
        cells[p] = (236, 236, 230, 150)  # dust
    return item(cells)


def money():
    cells = {}
    paper, paper_d, ink = (132, 176, 140), (98, 140, 108), (52, 92, 70)
    for y in range(4, 13):
        for x in range(1, 15):
            c = paper if (x + y) % 7 else sh(paper, 1.06)
            if y in (4, 12) or x in (1, 14):
                c = paper_d
            cells[(x, y)] = c
    for y in range(6, 11):
        for x in range(6, 10):
            if math.hypot(x + 0.5 - 8, (y + 0.5 - 8.5) * 0.8) < 2.2:
                cells[(x, y)] = (200, 220, 196)  # watermark oval
    cells[(7, 8)] = ink
    cells[(8, 8)] = ink
    cells[(7, 9)] = ink
    for x, y in ((2, 5), (3, 5), (12, 11), (13, 11), (2, 11), (13, 5)):
        cells[(x, y)] = ink  # denomination in the corners
    cells[(3, 9)] = (200, 160, 60)
    cells[(12, 7)] = (200, 160, 60)
    return item(cells, outline_of=True)


def rag():
    cells = {}
    rnd = random.Random(4)
    base = (176, 168, 150)
    for y in range(3, 14):
        for x in range(2, 15):
            if math.hypot((x - 8) / 6.2, (y - 8.5) / 5.0) <= 1.0 + 0.12 * math.sin(x * 1.3 + y):
                fold = math.sin((x + y * 0.6) * 0.9)
                c = sh(base, 1.0 + 0.12 * fold)
                if rnd.random() < 0.08:
                    c = (120, 104, 84)  # stain
                cells[(x, y)] = c
    return item(cells, outline_of=True)


def sheet(pattern):
    """A rolled-up painted sheet (the fake wall / fake vent): the pattern with a curled corner."""
    cells = {}
    for y in range(2, 14):
        for x in range(2, 14):
            if x + (13 - y) > 21:
                continue
            cells[(x, y)] = pattern(x, y)
    for k in range(4):
        cells[(10 + k, 6 - k + 4)] = (236, 226, 200)  # the curl, the back of the sheet
    cells[(11, 9)] = (210, 196, 166)
    cells[(12, 8)] = (210, 196, 166)
    return item(cells, outline_of=True)


def fake_wall():
    def pat(x, y):
        row = (y - 2) // 3
        mortar = (y - 2) % 3 == 2 or ((x + (3 if row % 2 else 0)) % 6 == 0)
        return (120, 120, 124) if mortar else sh((168, 168, 170), 1.0 + 0.05 * ((x * 7 + y * 3) % 4 - 1.5))
    return sheet(pat)


def fake_vent():
    def pat(x, y):
        if x in (2, 13) or y in (2, 13):
            return (110, 112, 118)
        return (60, 62, 68) if y % 2 == 0 else (176, 178, 184)
    return sheet(pat)


# ---------------------------------------------------------------- blocks

def tex(fn, seed=0):
    r = random.Random(seed)
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            c = fn(x, y, r)
            px[x, y] = tuple(clamp(v) for v in c[:3]) + ((c[3],) if len(c) == 4 else (255,))
    return img


def stone(c, r, amount=10):
    d = r.randint(-amount, amount)
    return tuple(v + d for v in c)


def prison_wall(x, y, r, cracked=False):
    # Four courses of bricks, offset every other course; dark mortar, bevelled edges.
    course = y // 4
    off = 4 if course % 2 else 0
    bx = (x + off) % 8
    by = y % 4
    if by == 3 or bx == 7:
        return stone((92, 92, 96), r, 5)
    base = (150, 150, 152) if (course + (x + off) // 8) % 2 else (140, 140, 144)
    if by == 0 or bx == 0:
        base = sh(base, 1.12)
    elif by == 2 or bx == 6:
        base = sh(base, 0.9)
    return stone(base, r, 7)


CRACK = {(8, 1), (7, 2), (7, 3), (6, 4), (6, 5), (7, 6), (8, 7), (8, 8), (9, 9), (10, 10), (9, 11), (9, 12), (10, 13),
         (5, 5), (4, 6), (3, 6), (11, 10), (12, 11)}


def cracked_wall(x, y, r):
    c = prison_wall(x, y, r)
    if (x, y) in CRACK:
        return (46, 44, 48)
    if (x - 1, y) in CRACK or (x, y - 1) in CRACK:
        return sh(c, 0.82)
    return c


def prison_floor(x, y, r):
    # Large worn tiles with grout; a faint diagonal sheen.
    if x % 8 == 7 or y % 8 == 7:
        return stone((126, 124, 120), r, 4)
    tile = (196, 194, 188) if ((x // 8) + (y // 8)) % 2 else (186, 184, 178)
    c = sh(tile, 1.0 + 0.04 * math.sin((x + y) * 0.5))
    if r.random() < 0.05:
        c = sh(c, 0.9)
    return stone(c, r, 5)


def reinforced_wall(x, y, r):
    # A riveted steel plate: frame, two panels, rivets, a few scratches.
    if x in (0, 15) or y in (0, 15):
        return stone((54, 56, 60), r, 3)
    if x in (1, 14) or y in (1, 14):
        return stone((96, 98, 104), r, 4)
    if (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13), (7, 2), (8, 13)):
        return (176, 178, 184)
    if y == 7 or y == 8:
        return stone((70, 72, 78) if y == 8 else (112, 114, 120), r, 3)
    c = (88, 90, 96)
    c = sh(c, 1.0 + 0.08 * math.sin(x * 0.7 + y * 0.2))
    if r.random() < 0.04:
        c = (124, 126, 132)
    return stone(c, r, 4)


def cell_bars(x, y, r):
    # Round bars with a light side and a shadow side; two cross braces.
    cols = {1: 0, 2: 1, 3: 2, 7: 0, 8: 1, 9: 2, 13: 0, 14: 1, 15: 2}
    if y in (1, 2) or y in (13, 14):
        k = 1.15 if y in (1, 13) else 0.82
        return stone(sh((110, 112, 120), k), r, 3)
    if x in cols:
        k = (1.25, 1.0, 0.72)[cols[x]]
        return stone(sh((112, 114, 122), k), r, 3)
    return (0, 0, 0, 0)


def vent_cover(x, y, r):
    if x in (0, 15) or y in (0, 15):
        return stone((70, 72, 78), r, 3)
    if (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        return (190, 190, 196)  # screws
    if x in (1, 14) or y in (1, 14):
        return stone((128, 130, 136), r, 4)
    # Angled slats: lit top edge, dark gap.
    k = y % 3
    c = (160, 162, 168) if k == 0 else ((120, 122, 128) if k == 1 else (36, 38, 42))
    if r.random() < 0.06 and k != 2:
        c = (150, 100, 66)  # rust
    return stone(c, r, 4)


def job_desk_top(x, y, r):
    if x in (0, 15) or y in (0, 15):
        return stone((96, 62, 36), r, 4)
    # Planks along x, a ledger, a stamp and a pencil.
    if 3 <= x <= 9 and 3 <= y <= 11:
        if x == 6:
            return (150, 140, 120)  # spine
        if y in (5, 7, 9) and x not in (3, 9):
            return (150, 150, 170)  # lines
        return stone((236, 232, 218), r, 3)
    if (x, y) in ((12, 4), (12, 5), (13, 4), (13, 5)):
        return (170, 40, 40)  # stamp
    if (x, y) == (12, 3) or (x, y) == (13, 3):
        return (60, 40, 30)
    if y == 12 and 10 <= x <= 14:
        return (220, 180, 60) if x < 14 else (60, 60, 60)  # pencil
    plank = (y - 1) // 3
    c = (150, 100, 58) if plank % 2 else (138, 92, 52)
    if (y - 1) % 3 == 2:
        c = sh(c, 0.8)
    return stone(sh(c, 1.0 + 0.05 * math.sin(x * 1.1 + plank)), r, 5)


def job_desk_side(x, y, r):
    if y in (0, 1):
        return stone((110, 72, 42), r, 4)
    if x in (0, 15) or y == 15:
        return stone((84, 54, 32), r, 4)
    if 4 <= x <= 11 and 4 <= y <= 8:
        if x in (4, 11) or y in (4, 8):
            return stone((92, 60, 34), r, 3)
        if y == 6 and 7 <= x <= 8:
            return (210, 176, 70)  # handle
        return stone((132, 88, 50), r, 4)
    return stone(sh((126, 84, 48), 1.0 + 0.06 * math.sin(y * 0.9 + x * 0.2)), r, 5)


def detector_side(x, y, r):
    c = mixc((120, 124, 134), (82, 86, 96), x / 15)  # brushed metal, lit from the left
    if y % 5 == 0:
        c = sh(c, 0.85)
    if (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        c = (190, 192, 200)
    return stone(c, r, 3)


def detector_panel(x, y, r):
    if x in (0, 15) or y in (0, 15):
        return stone((50, 52, 58), r, 2)
    if y % 3 == 1 and 2 <= x <= 13:
        return (60, 200, 100) if x < 6 + (y * 3) % 7 else (34, 70, 46)  # level bars
    return stone((26, 28, 32), r, 2)


def lamp(color):
    def f(x, y, r):
        d = math.hypot(x - 7.5, y - 7.5)
        if d > 7.6:
            return (46, 48, 52)
        if d > 6.4:
            return (90, 92, 98)
        k = 1.25 - d / 9
        c = sh(color, k)
        if d < 2.4 and color != (60, 62, 66):
            c = mixc(c, (255, 255, 255), 0.5)
        return stone(c, r, 2)
    return f


def chalk_graffiti(img, seed):
    """Turns a flat drawing into chalk on the wall: uneven strokes with gaps and a few specks of dust
    (graffiti renders as cutout, so no half-transparent pixels)."""
    r = random.Random(seed)
    src = img.convert("RGBA")
    out = Image.new("RGBA", src.size, (0, 0, 0, 0))
    sp, op = src.load(), out.load()
    w, h = src.size
    for y in range(h):
        for x in range(w):
            c = sp[x, y]
            if c[3] > 0 and r.random() > 0.07:
                k = 0.86 + 0.18 * r.random()
                op[x, y] = tuple(clamp(v * k) for v in c[:3]) + (255,)
    for y in range(h):
        for x in range(w):
            if sp[x, y][3] == 0 and r.random() < 0.03:
                near = [sp[x + dx, y + dy] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                        if 0 <= x + dx < w and 0 <= y + dy < h and sp[x + dx, y + dy][3] > 0]
                if near:
                    op[x, y] = sh(near[0], 0.8) + (255,)
    return out


# ---------------------------------------------------------------- faces and skins

def face_shading(face, x, y, look, base):
    """Soft shading on a face: darker sides, a lit nose, cheek colour."""
    if base != look["skin"]:
        return base
    if face == "front":
        if x in (0, 7):
            return sh(base, 0.9)
        if y == 5 and x in (3, 4):
            return sh(base, 1.06)
        if y == 5 and x in (1, 6):
            return mixc(base, (210, 120, 110), 0.15)
    if face in ("left", "right") and y >= 5:
        return sh(base, 0.92)
    return base


def head_v6(face, x, y, look):
    c = g.person_head(face, x, y, look)
    if c is None:
        return c
    c = face_shading(face, x, y, look, c)
    if face == "front" and y == 4 and x in (2, 5):
        return look["eyes"]
    if face == "front" and y == 4 and x in (1, 6):
        return (236, 236, 232)
    if face == "front" and y == 3 and x in (2, 5) and look["style"] not in ("bald",):
        return sh(look["hair"], 0.85) if look["style"] != "beanie" else look["hair"]
    return c


ORANGE = (226, 110, 30)

TATTOO_INK = (44, 60, 96)


def tattoo_marks(look):
    """Where on the skin the tattoos go: {(part, face, x, y): colour}."""
    r = random.Random(look["seed"] * 31 + 7)
    marks = {}
    ink = mixc(TATTOO_INK, look["skin"], 0.12)
    red = (150, 46, 46)

    def put(part, face, x, y, c=ink):
        marks[(part, face, x, y)] = c

    for kind in look["tattoos"]:
        arm = r.choice(("right_arm", "left_arm"))
        outer = "right" if arm == "right_arm" else "left"
        if kind == "star":  # thieves' star on the shoulder
            for dx, dy in ((1, 0), (0, 1), (1, 1), (2, 1), (1, 2)):
                put(arm, outer, dx + 1, dy + 1)
            put(arm, "front", 1, 2)
        elif kind == "sleeve":  # a full sleeve of patterns
            ph = look["seed"] * 1.37
            for face_i, face in enumerate(("front", outer, "back")):
                for y in range(1, 10):
                    for x in range(4):
                        u = x + face_i * 4  # one continuous pattern round the arm
                        v = math.sin(u * 0.9 + y * 0.55 + ph) + math.sin(y * 1.1 - u * 0.45 + ph * 2)
                        if v > 0.7:
                            put(arm, face, x, y)
        elif kind == "dagger":
            for y in range(5, 10):
                put(arm, "front", 1 if y > 5 else 2, y, red if y == 5 else ink)
            put(arm, "front", 0, 6)
            put(arm, "front", 2, 6)
        elif kind == "cross":
            for y in range(5, 9):
                put(arm, "front", 2, y)
            put(arm, "front", 1, 6)
            put(arm, "front", 3, 6)
        elif kind == "web":  # spider web on the elbow
            for x, y in ((0, 4), (1, 5), (2, 4), (3, 5), (1, 3), (2, 6)):
                put(arm, outer, x, y)
        elif kind == "bands":
            for x in range(4):
                for face in ("front", "back", "left", "right"):
                    put(arm, face, x, 7)
                    put(arm, face, x, 8 if x % 2 else 7)
        elif kind == "knuckles":  # dots on the knuckles of both hands
            for a in ("right_arm", "left_arm"):
                for x in range(4):
                    put(a, "front", x, 10 if x % 2 == 0 else 11)
        elif kind == "dots":  # five dots on the hand
            for x, y in ((0, 10), (2, 10), (1, 11), (0, 11), (2, 11)):
                put(arm, "front", x, y)
        elif kind == "rose":
            for x, y in ((1, 5), (2, 5), (1, 6), (2, 6)):
                put(arm, "front", x, y, red)
            put(arm, "front", 2, 7, (50, 110, 60))
            put(arm, "front", 1, 8, (50, 110, 60))
        elif kind == "neck":  # a web or letters on the neck
            for x in (2, 3, 4, 5):
                put("body", "front", x, 0, ink if x % 2 else mixc(ink, look["skin"], 0.4))
        elif kind == "chest":  # domes on the chest, shown when the jumpsuit is open
            for x, y in ((3, 2), (4, 2), (2, 3), (3, 3), (4, 3), (5, 3), (3, 1)):
                put("body", "front", x, y)
        elif kind == "face_star":
            put("head", "front", 6, 5, ink)
        elif kind == "tear":
            put("head", "front", 1, 5, ink)
    return marks


def prisoner_v6(look, trader=False):
    """An inmate: orange jumpsuit worn in one of several ways, tattoos on whatever skin shows,
    hats and hair on the outer layer."""
    marks = tattoo_marks(look)
    wear = look["wear"]  # full, rolled, tank (top tied round the waist), open
    o = look["orange"]
    o_d = sh(o, 0.82)
    tank = look["tank"]
    num = look["number"]
    skin = look["skin"]

    def suit(part, face, x, y, h):
        c = sh(o, 1.06 - 0.14 * y / max(1, h - 1))
        if (x * 5 + y * 3 + look["seed"]) % 11 == 0:
            c = sh(c, 0.93)  # wear and dirt
        return c

    def skin_px(part, face, x, y):
        k = marks.get((part, face, x, y))
        if k:
            return k
        return skin if face != "bottom" else sh(skin, 0.9)

    def head(face, x, y):
        k = marks.get(("head", face, x, y))
        if k:
            return k
        return head_v6(face, x, y, look)

    def hat(face, x, y):
        style = look["hat"]
        if trader:
            if face == "bottom":
                return None
            if face == "top" or y <= 1:
                return (44, 150, 64) if (x + y) % 3 else (30, 112, 46)
            if face == "back" and y <= 3 and x in (3, 4):
                return (30, 112, 46)  # knot
            return None
        if style == "beanie":
            cap = look["cap"]
            if face == "top":
                return sh(cap, 0.92) if (x + y) % 2 else cap
            if face != "bottom" and y <= 1:
                return sh(cap, 1.1) if y == 1 and x % 2 else cap
            return None
        if style == "bandana":
            b = look["cap"]
            if face == "top":
                return (236, 236, 236) if (x * 3 + y * 5) % 13 == 0 else b
            if face != "bottom" and y == 0:
                return b
            if face == "front" and y == 1:
                return b
            if face == "back" and y <= 3 and x in (3, 4):
                return b
            return None
        if style == "cap":  # a cap turned backwards
            b = look["cap"]
            if face == "top":
                return b
            if face == "back" and y in (1, 2):
                return sh(b, 0.7) if y == 2 else b  # peak at the back
            if face != "bottom" and y == 0:
                return b
            if face == "front" and y == 1 and 3 <= x <= 4:
                return (236, 236, 236)
            return None
        if style == "hair":  # thick hair over the head
            hc = look["hair"]
            if face == "top":
                return sh(hc, 1.08) if (x + 2 * y) % 5 == 0 else hc
            if face == "bottom":
                return None
            if y <= 1 or (face == "back" and y <= 5) or (face in ("left", "right") and y <= 3 and (x < 3 or face == "back")):
                return sh(hc, 0.9) if (x + y) % 3 == 0 else hc
            return None
        return None

    def body(face, x, y, h):
        if face in ("top", "bottom"):
            return suit("body", face, x, y, h)
        if wear == "tank" and y < 9:
            # Tank top; the jumpsuit top is tied round the waist.
            if face == "front" and (x in (0, 7) or y == 0 and 2 <= x <= 5):
                return skin_px("body", face, x, y)
            if face in ("left", "right") and y < 3:
                return skin_px("body", face, x, y)
            if face == "back" and x in (0, 7):
                return skin_px("body", face, x, y)
            return sh(tank, 1.0 - 0.06 * (y % 3 == 2))
        if y >= 9:
            if y == 9 and wear == "tank":
                return sh(o, 0.75) if (x + y) % 2 else o  # the knotted sleeves
            return suit("body", face, x, y, h)
        if face == "front":
            if wear == "open" and 3 <= x <= 4 and y <= 6:
                return skin_px("body", face, x, y) if y < 4 else tank
            if y == 0 and 2 <= x <= 5:
                return skin_px("body", face, x, y) if wear != "full" else (236, 236, 236)
            if 1 <= x <= 2 and 2 <= y <= 3:
                # The number patch on the chest.
                return (236, 236, 228) if not (x == 2 and y == 3) else (40, 40, 44)
            if x == 1 and y == 3:
                return (40, 40, 44)
            if x in (3, 4) and y > 0 and wear != "open":
                return sh(o, 0.86) if y % 3 else (200, 200, 196)  # zip line and buttons
        if face == "back" and 2 <= y <= 5 and 1 <= x <= 6:
            # Prison number stencilled on the back: dark bars that read as letters from afar.
            return (40, 40, 44) if (x + num) % 3 != 2 and y in (3, 4) else suit("body", face, x, y, h)
        return suit("body", face, x, y, h)

    def jacket(face, x, y, h):
        if trader and face == "front" and y == 1 and 1 <= x <= 6:
            return (232, 192, 64) if x % 2 else (200, 160, 40)  # gold chain
        if wear == "full" and face == "front" and y == 9:
            return (52, 52, 56) if not 3 <= x <= 4 else (190, 190, 196)  # belt
        return None

    def arm(part, face, x, y, h):
        sleeve_end = {"full": 10, "rolled": 5, "tank": 0, "open": 10}[wear]
        if face == "bottom" or y >= 10:
            return skin_px(part, face if face != "bottom" else "front", x, min(y, 11))
        if face == "top":
            return skin_px(part, face, x, y) if sleeve_end == 0 else suit(part, face, x, y, h)
        if y < sleeve_end:
            if wear == "rolled" and y == sleeve_end - 1:
                return sh(o, 0.78)  # the roll
            return suit(part, face, x, y, h)
        return skin_px(part, face, x, y)

    def leg(part, face, x, y, h):
        if face == "bottom" or y >= 10:
            shoe = look["shoes"]
            if y == 10 and face != "bottom":
                return (236, 236, 232) if look["socks"] else shoe
            return shoe
        if face == "front" and y in (4, 5):
            return sh(o, 0.88)  # knee
        return suit(part, face, x, y, h)

    def pants(face, x, y, h):
        if wear == "rolled" and face != "top" and face != "bottom" and y == 8:
            return sh(o, 0.76)  # trousers rolled up a little
        return None

    def paint(part, face, x, y, w, h):
        if part == "head":
            return head(face, x, y)
        if part == "hat":
            return hat(face, x, y)
        if part == "body":
            return body(face, x, y, h)
        if part == "jacket":
            return jacket(face, x, y, h)
        if part in ("right_arm", "left_arm"):
            return arm(part, face, x, y, h)
        if part in ("right_leg", "left_leg"):
            return leg(part, face, x, y, h)
        if part in ("right_pants", "left_pants"):
            return pants(face, x, y, h)
        return None
    return paint


TATTOO_KINDS = ["star", "sleeve", "dagger", "cross", "web", "bands", "knuckles", "dots", "rose", "neck", "chest",
                "face_star", "tear"]


def prisoner_look(i):
    r = random.Random(6100 + i)
    look = g.make_look(r)
    look["seed"] = i
    look["wear"] = ["full", "rolled", "tank", "open"][i % 4] if i < 12 else r.choice(["rolled", "tank", "open", "full"])
    look["hat"] = r.choice(["none", "none", "beanie", "bandana", "cap", "hair"])
    if look["hat"] == "beanie":
        look["style"] = "beanie"
    if look["hat"] == "hair":
        look["style"] = r.choice(["short", "long"])
    look["cap"] = r.choice([(40, 40, 46), (70, 70, 76), (150, 40, 40), (40, 70, 130), (80, 60, 40)])
    look["orange"] = r.choice([(226, 110, 30), (232, 120, 36), (214, 98, 28), (236, 132, 44)])
    look["tank"] = r.choice([(232, 232, 228), (180, 180, 176), (60, 60, 64)])
    look["shoes"] = r.choice([(236, 236, 232), (40, 40, 44), (90, 90, 96)])
    look["socks"] = r.random() < 0.5
    look["number"] = r.randint(0, 9)
    n = r.choice([1, 2, 2, 3, 3, 4]) if i != 3 else 0
    kinds = TATTOO_KINDS[:]
    r.shuffle(kinds)
    look["tattoos"] = kinds[:n]
    look["tear"] = False
    look["scar"] = r.random() < 0.2
    return look


def cook_v6(look, cheerful):
    white, white_d = (238, 238, 234), (212, 212, 206)
    accent = (200, 50, 50) if cheerful else (70, 70, 80)

    def paint(part, face, x, y, w, h):
        if part == "head":
            return head_v6(face, x, y, look)
        if part == "hat":  # chef's toque: tall white crown on the outer layer
            if face == "bottom":
                return None
            if face == "top":
                return white if (x + y) % 3 else white_d
            if y <= 2:
                return white_d if (x % 3 == 0 and y < 2) else white
            return None
        if part == "body":
            if face == "front":
                if y == 0 and 2 <= x <= 5:
                    return accent if cheerful else white_d  # neckerchief
                if 2 <= x <= 5 and y >= 3:
                    c = (226, 226, 220)  # apron
                    if (x * 7 + y * 5) % 9 == 0:
                        c = (196, 170, 140) if not cheerful else (210, 120, 100)  # stains
                    return c
                if x in (1, 6) and y in (2, 4, 6):
                    return (60, 60, 64)  # double-breasted buttons
            if y >= 10:
                return (54, 54, 60) if (x + y) % 2 else (64, 64, 70)  # chequered trousers
            return white if y < 9 else white_d
        if part == "jacket":
            if face == "back" and y == 6 and 2 <= x <= 5:
                return (200, 200, 196)  # apron strings
            return None
        if part in ("right_arm", "left_arm"):
            if face == "bottom" or y >= 9:
                return look["skin"]
            return white_d if y == 8 else white
        if part in ("right_leg", "left_leg"):
            if face == "bottom" or y >= 10:
                return (30, 30, 34)
            return (54, 54, 60) if (x + y) % 2 else (64, 64, 70)
        return None
    return paint


# ---------------------------------------------------------------- main

def main(root):
    item_dir = os.path.join(root, "textures", "item")
    block_dir = os.path.join(root, "textures", "block")
    entity_dir = os.path.join(root, "textures", "entity")

    items = {"makeshift_pickaxe": makeshift_pickaxe(), "makeshift_shovel": makeshift_shovel(), "file": file_tool(),
             "shiv": shiv(), "baton": baton(), "handcuffs": handcuffs(), "cell_key": cell_key(), "chalk": chalk(),
             "money": money(), "rag": rag(), "fake_wall": fake_wall(), "fake_vent": fake_vent()}
    for name, img in items.items():
        img.save(os.path.join(item_dir, name + ".png"))
    for name, broken in (("makeshift_pickaxe", "broken_pickaxe"), ("makeshift_shovel", "broken_shovel"),
                         ("file", "broken_file")):
        g.cut_broken(items[name]).save(os.path.join(item_dir, broken + ".png"))

    blocks = {"prison_wall": (prison_wall, 1), "cracked_prison_wall": (cracked_wall, 1), "prison_floor": (prison_floor, 2),
              "reinforced_wall": (reinforced_wall, 3), "cell_bars": (cell_bars, 4), "vent_cover": (vent_cover, 5),
              "job_desk_top": (job_desk_top, 6), "job_desk_side": (job_desk_side, 7), "detector_side": (detector_side, 8),
              "detector_panel": (detector_panel, 9), "detector_lamp_off": (lamp((60, 62, 66)), 10),
              "detector_lamp_green": (lamp((60, 210, 90)), 11), "detector_lamp_red": (lamp((230, 50, 40)), 12),
              "detector": (detector_side, 13)}
    for name, (fn, seed) in blocks.items():
        tex(fn, seed).save(os.path.join(block_dir, name + ".png"))
    for i in range(16):
        path = os.path.join(block_dir, "graffiti_%d.png" % i)
        flat = g.graffiti_img(g.GRAFFITI[i])
        chalk_graffiti(flat, 900 + i).save(path)

    for i in range(16):
        look = prisoner_look(i)
        g.paint_skin_layers(prisoner_v6(look), 7700 + i).save(os.path.join(entity_dir, "prisoner_%d.png" % i))
        g.paint_skin_layers(prisoner_v6(look, trader=True), 7800 + i).save(
            os.path.join(entity_dir, "prisoner_trader_%d.png" % i))
    g.paint_skin_layers(prisoner_v6(prisoner_look(0)), 7690).save(os.path.join(entity_dir, "prisoner.png"))
    g.paint_skin_layers(prisoner_v6(prisoner_look(0), True), 7691).save(os.path.join(entity_dir, "prisoner_trader.png"))

    gloomy = g.make_look(random.Random(9001))
    gloomy.update({"style": "short", "beard": "mustache", "hair": (150, 150, 150)})
    cheerful = g.make_look(random.Random(9002))
    cheerful.update({"style": "short", "beard": "none", "scar": False, "tear": False})
    g.paint_skin_layers(cook_v6(gloomy, False), 9101).save(os.path.join(entity_dir, "cook_0.png"))
    g.paint_skin_layers(cook_v6(cheerful, True), 9102).save(os.path.join(entity_dir, "cook_1.png"))

    # Guards keep their uniforms (0.3.8); their faces get the new shading.
    g.person_head_plain = g.person_head
    for i, uni in enumerate(g.GUARD_V5):
        r = random.Random(8100 + i)
        look = g.make_look(r)
        look["style"] = r.choice(["short", "buzz", "bald", "short"])
        look["beard"] = r.choice(["none", "none", "mustache", "stubble"])
        look["scar"] = r.random() < 0.15
        look["tear"] = False
        look["shades"] = r.random() < 0.3
        look["glasses"] = not look["shades"] and r.random() < 0.2
        look["balaclava"] = i == 6
        painter = g.guard_v5_painter(look, uni, 8200 + i)

        def shaded(part, face, x, y, w, h, painter=painter, look=look):
            c = painter(part, face, x, y, w, h)
            if part == "head" and c is not None and not look["balaclava"]:
                return face_shading(face, x, y, look, c)
            return c
        g.paint_skin_layers(shaded, 8300 + i).save(os.path.join(entity_dir, "guard_%d.png" % i))
    g.paint_skin_layers(shaded, 8399).save(os.path.join(entity_dir, "guard.png"))


if __name__ == "__main__":
    main(sys.argv[1])
