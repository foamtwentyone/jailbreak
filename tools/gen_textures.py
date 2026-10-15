"""Generate Cellbreak textures (skins, blocks, items) procedurally.

Usage: python3 gen_textures.py <assets_dir>
Writes PNGs into <assets_dir>/textures/{entity,block,item}.
"""
import os
import random
import sys

from PIL import Image

rng = random.Random(1337)


def jitter(c, amount=10):
    d = rng.randint(-amount, amount)
    return tuple(max(0, min(255, v + d)) for v in c[:3]) + ((c[3],) if len(c) == 4 else (255,))


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (255,)


# ---------------------------------------------------------------- skins

# Box UV layout used by Minecraft biped skins (64x64).
PARTS = {
    "head": (0, 0, 8, 8, 8),
    "body": (16, 16, 8, 12, 4),
    "right_arm": (40, 16, 4, 12, 4),
    "left_arm": (32, 48, 4, 12, 4),
    "right_leg": (0, 16, 4, 12, 4),
    "left_leg": (16, 48, 4, 12, 4),
}


def face_rects(u, v, w, h, d):
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


def paint_skin(painter):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = img.load()
    for part, (u, v, w, h, d) in PARTS.items():
        for face, (fx, fy, fw, fh) in face_rects(u, v, w, h, d).items():
            for y in range(fh):
                for x in range(fw):
                    c = painter(part, face, x, y, fw, fh)
                    if c is not None:
                        px[fx + x, fy + y] = jitter(c, 6)
    return img


SKIN = (214, 162, 122)
SKIN_DARK = (188, 134, 98)


def head_common(face, x, y, hair, hair_rows, eyes):
    """Head with hair/cap on top rows; eyes drawn by `eyes`."""
    if face == "top":
        return hair
    if face == "bottom":
        return SKIN_DARK
    if y < hair_rows:
        return hair
    if face == "front":
        e = eyes(x, y)
        if e is not None:
            return e
        if y == 6 and 3 <= x <= 4:
            return (150, 90, 70)  # mouth
        return SKIN
    if face == "back":
        return hair if y < hair_rows + 2 else SKIN_DARK
    # sides
    if face in ("left", "right") and y == 4 and x in (3, 4):
        return SKIN_DARK  # ear
    return SKIN


def guard_painter(part, face, x, y, w, h):
    navy = (34, 46, 84)
    navy_d = (26, 34, 62)
    black = (22, 22, 26)
    gold = (226, 182, 54)
    if part == "head":
        def eyes(x, y):
            if y == 3 and 1 <= x <= 6:
                return (16, 16, 20)  # visor shadow
            if y == 4 and (1 <= x <= 2 or 5 <= x <= 6):
                return (20, 20, 24)  # sunglasses lenses
            if y == 4 and 3 <= x <= 4:
                return (60, 60, 64)  # bridge
            return None
        if face == "front" and y == 2:
            return black  # cap visor
        if face == "front" and y < 2 and 3 <= x <= 4:
            return gold  # cap badge
        return head_common(face, x, y, navy, 3 if face != "front" else 2, eyes)
    if part == "body":
        if face == "front":
            if y == 9:
                return (232, 200, 80) if 3 <= x <= 4 else black  # belt + buckle
            if y > 9:
                return navy_d
            if x == 2 and y in (2, 3):
                return gold  # badge
            if y == 0 and 3 <= x <= 4:
                return (200, 200, 205)  # collar
            if x in (3, 4) and y > 0:
                return (30, 40, 74) if y % 3 else (180, 180, 190)  # placket + buttons
            return navy
        if face in ("top", "bottom"):
            return navy
        if y == 9:
            return black
        return navy if y < 9 else navy_d
    if part in ("right_arm", "left_arm"):
        if face == "bottom" or y >= 10:
            return SKIN  # hands
        if y == 3 and face in ("front", "back", "left", "right"):
            return (230, 230, 235) if part == "left_arm" else navy_d  # left shoulder stripe
        return navy
    # legs
    if face == "bottom" or y >= 10:
        return black  # boots
    if face in ("left", "right") and x == 1:
        return (60, 70, 110)  # side stripe
    return navy_d


def prisoner_painter(part, face, x, y, w, h):
    orange = (230, 118, 30)
    orange_d = (196, 96, 22)
    white = (236, 236, 236)
    if part == "head":
        def eyes(x, y):
            if y == 4 and x in (1, 6):
                return (245, 245, 245)
            if y == 4 and x in (2, 5):
                return (60, 42, 30)
            if y == 3 and (1 <= x <= 2 or 5 <= x <= 6):
                return (70, 50, 38)  # brows
            return None
        return head_common(face, x, y, (70, 52, 40), 1, eyes)
    if part == "body":
        if face == "front":
            if y == 0 and 2 <= x <= 5:
                return white  # undershirt at neck
            if 2 <= y <= 3 and 4 <= x <= 7:
                return (24, 24, 24) if (x + y) % 2 else white  # number patch
            if y == 10:
                return orange_d
            return orange
        if face == "back" and 2 <= y <= 5 and 1 <= x <= 6:
            return (24, 24, 24) if (y in (2, 5) or x in (1, 6)) else orange  # back stencil frame
        return orange if y < 10 else orange_d
    if part in ("right_arm", "left_arm"):
        if face == "bottom" or y >= 10:
            return SKIN
        if y < 4:
            return orange
        return SKIN  # short sleeves
    if face == "bottom" or y >= 11:
        return (210, 210, 210)  # white sneakers
    return orange_d if y >= 10 else orange


# ---------------------------------------------------------------- blocks

def tex16(fn):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            c = fn(x, y)
            if c is not None:
                px[x, y] = c
    return img


def prison_wall(x, y):
    row = y // 4
    off = 4 if row % 2 else 0
    mortar = y % 4 == 3 or (x + off) % 8 == 7
    base = (150, 150, 146)
    if mortar:
        return jitter((112, 112, 110), 4)
    c = jitter(base, 9)
    if y % 4 == 0:
        c = shade(c, 1.08)
    return c


def reinforced_wall(x, y):
    if x in (0, 15) or y in (0, 15):
        return jitter((72, 74, 80), 4)
    if (x in (2, 13)) and (y in (2, 13)):
        return (170, 172, 178, 255)  # bolts
    c = jitter((104, 106, 112), 7)
    return shade(c, 0.92) if (x + y) % 5 == 0 else c


def cracked_wall(x, y):
    c = prison_wall(x, y)
    crack = {(7, 2), (7, 3), (8, 4), (8, 5), (7, 6), (6, 7), (6, 8), (7, 9), (8, 10),
             (9, 11), (9, 12), (5, 8), (4, 9), (3, 9), (9, 5), (10, 5), (11, 6)}
    if (x, y) in crack:
        return (44, 42, 40, 255)
    return c


def cell_bars(x, y):
    if x % 4 == 1 or x % 4 == 2:
        hi = x % 4 == 1
        return jitter((128, 132, 140) if hi else (92, 96, 104), 5)
    if y in (1, 14) or y in (2, 13):
        return jitter((84, 88, 96), 4)
    return None


def vent_cover(x, y):
    if x in (0, 15) or y in (0, 15):
        return jitter((150, 154, 160), 4)
    if (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        return (90, 90, 96, 255)
    if y % 3 == 1:
        return jitter((52, 54, 60), 3)
    return jitter((176, 180, 186), 5)


def prison_floor(x, y):
    tile = ((x // 8) + (y // 8)) % 2
    if x % 8 == 0 or y % 8 == 0:
        return jitter((140, 140, 136), 3)
    return jitter((206, 204, 196) if tile else (178, 176, 168), 5)


# ---------------------------------------------------------------- items

def from_pixels(rows, palette):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, y] = palette[ch] + (255,)
    return img


OUT = (30, 28, 32)

ITEMS = {
    "shiv": ([
        "................",
        "..............o.",
        ".............ows",
        "............owso",
        "...........owso.",
        "..........owso..",
        ".........owso...",
        "........oooo....",
        ".......obbo.....",
        "......obbo......",
        ".....obbo.......",
        "....obbo........",
        "...obbo.........",
        "..obbo..........",
        "..ooo...........",
        "................",
    ], {"o": OUT, "w": (230, 232, 238), "s": (150, 156, 170), "b": (60, 140, 220)}),
    "file": ([
        "................",
        "............oo..",
        "...........ogho.",
        "..........ogxgo.",
        ".........ogxgo..",
        "........ogxgo...",
        ".......ogxgo....",
        "......ogxgo.....",
        ".....ooooo......",
        "....owwo........",
        "...owwo.........",
        "..owwo..........",
        "..ooo...........",
        "................",
        "................",
        "................",
    ], {"o": OUT, "g": (130, 134, 142), "h": (200, 204, 212), "x": (88, 92, 100), "w": (150, 100, 60)}),
    "makeshift_pickaxe": ([
        "................",
        "....ooooooo.....",
        "...osssssssoo...",
        "..oso.oowoo.so..",
        "..oo...owo..oo..",
        ".......owo......",
        "......owo.......",
        ".....owo........",
        "....owo.........",
        "...ott..........",
        "..owo...........",
        ".owo............",
        ".oo.............",
        "................",
        "................",
        "................",
    ], {"o": OUT, "s": (170, 174, 182), "w": (176, 128, 80), "t": (220, 220, 210)}),
    "cell_key": ([
        "................",
        "................",
        "...ooooo........",
        "..oyyyyyo.......",
        "..oyo.oyo.......",
        "..oyo.oyo.......",
        "..oyyyyyoooooo..",
        "...ooooyyyyyyyo.",
        ".......oooyoyo..",
        ".........oyoyo..",
        "..........o.o...",
        "................",
        "................",
        "................",
        "................",
        "................",
    ], {"o": OUT, "y": (120, 200, 90)}),
    "makeshift_shovel": ([
        "................",
        "..........ooo...",
        ".........ossso..",
        "........osssslo.",
        "........ossssslo",
        ".........osssslo",
        "........otosslo.",
        ".......owto.oo..",
        "......owo.......",
        ".....owo........",
        "....owo.........",
        "...owo..........",
        "..owo...........",
        ".owo............",
        ".oo.............",
        "................",
    ], {"o": OUT, "s": (190, 190, 196), "l": (130, 132, 140), "w": (176, 128, 80), "t": (220, 220, 210)}),
    "handcuffs": ([
        "................",
        "................",
        "...ooo....ooo...",
        "..osssoooosss o.",
        ".os...o..o...so.",
        ".os...so.os...so",
        ".os...so.os...so",
        "..os.so...os.so.",
        "...ooo.....ooo..",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ], {"o": OUT, "s": (196, 200, 210), " ": (196, 200, 210)}),
    "baton": ([
        "................",
        "..............o.",
        ".............obo",
        "............obo.",
        "...........obo..",
        "..........obo...",
        ".........obo....",
        "........obo.....",
        ".......obo......",
        "......obo.......",
        "....oogoo.......",
        "....ogbo........",
        "...ogo..........",
        "..ogo...........",
        "..oo............",
        "................",
    ], {"o": OUT, "b": (40, 40, 48), "g": (120, 120, 130)}),
}


def trader_painter(part, face, x, y, w, h):
    """A prisoner who runs the black market: green bandana and a gold chain."""
    if part == "head" and (face == "top" or y < 2):
        return (40, 140, 60) if (x + y) % 3 else (30, 110, 45)
    if part == "body" and face == "front" and y == 1 and 1 <= x <= 6:
        return (230, 190, 60)
    return prisoner_painter(part, face, x, y, w, h)


def job_desk_top(x, y):
    if x in (0, 15) or y in (0, 15):
        return jitter((110, 74, 40), 4)
    if 2 <= x <= 7 and 3 <= y <= 10:
        return jitter((236, 232, 220), 3)  # work order sheet
    if 3 <= x <= 6 and y in (5, 7, 9):
        return (90, 90, 100, 255)
    if 10 <= x <= 12 and 2 <= y <= 12:
        return (150, 150, 160, 255) if x == 11 else jitter((140, 100, 60), 4)  # ruler
    return jitter((156, 112, 66), 6) if y % 4 else jitter((136, 96, 56), 4)


def job_desk_side(x, y):
    if y < 3:
        return jitter((136, 96, 56), 4)
    if x in (0, 1, 14, 15):
        return jitter((110, 74, 40), 4)
    if 4 <= x <= 11 and 6 <= y <= 9:
        return (60, 60, 66, 255) if (x, y) != (7, 7) else (200, 180, 70, 255)  # drawer + handle
    return jitter((150, 106, 62), 5)


def detector(x, y):
    frame = x in (0, 1, 14, 15) or y in (0, 1)
    if y in (0, 1) and 6 <= x <= 9:
        return (220, 40, 40, 255) if y == 1 else (255, 90, 90, 255)  # alarm lamp
    if frame:
        return jitter((150, 154, 162) if x in (0, 15) or y == 0 else (110, 114, 122), 4)
    return None


MORE_ITEMS = {
    "money": ([
        "................",
        "................",
        "................",
        ".oooooooooooooo.",
        ".oggggggggggggo.",
        ".ogllgggggglgo..",
        ".oglggg$$gggggo.",
        ".oggggg$$$ggggo.",
        ".oggggg$$gggggo.",
        ".oglggggggggllo.",
        ".oggggggggggggo.",
        ".oooooooooooooo.",
        "................",
        "................",
        "................",
        "................",
    ], {"o": (30, 70, 30), "g": (110, 180, 100), "l": (160, 220, 150), "$": (40, 110, 40)}),
    "fake_wall": ([
        "................",
        ".oooooooooooo...",
        ".owwwmwwwwwmo...",
        ".ommmmmmmmmmo...",
        ".owmwwwwwmwwwo..",
        ".ommmmmmmmmmmo..",
        ".owwwmwwwwwmwwo.",
        ".ommmmmmmmmmmmo.",
        ".owmwwwwwmwwwpo.",
        ".ommmmmmmmmmppo.",
        ".owwwmwwwwwpppo.",
        ".oooooooooooooo.",
        "................",
        "................",
        "................",
        "................",
    ], {"o": (40, 40, 40), "w": (160, 160, 156), "m": (112, 112, 110), "p": (236, 232, 220)}),
    "fake_vent": ([
        "................",
        ".oooooooooooo...",
        ".ossssssssssso..",
        ".ollllllllllso..",
        ".ossssssssssso..",
        ".ollllllllllso..",
        ".ossssssssssso..",
        ".ollllllllllsoo.",
        ".osssssssssppo..",
        ".olllllllllppo..",
        ".osssssssspppo..",
        ".oooooooooooo...",
        "................",
        "................",
        "................",
        "................",
    ], {"o": (40, 40, 40), "s": (176, 180, 186), "l": (52, 54, 60), "p": (236, 232, 220)}),
}


def main():
    root = sys.argv[1]
    dirs = {k: os.path.join(root, "textures", k) for k in ("entity", "block", "item")}
    for d in dirs.values():
        os.makedirs(d, exist_ok=True)

    paint_skin(guard_painter).save(os.path.join(dirs["entity"], "guard.png"))
    paint_skin(prisoner_painter).save(os.path.join(dirs["entity"], "prisoner.png"))

    for name, fn in {
        "prison_wall": prison_wall,
        "reinforced_wall": reinforced_wall,
        "cracked_prison_wall": cracked_wall,
        "cell_bars": cell_bars,
        "vent_cover": vent_cover,
        "prison_floor": prison_floor,
    }.items():
        tex16(fn).save(os.path.join(dirs["block"], name + ".png"))

    for name, (rows, pal) in ITEMS.items():
        from_pixels(rows, pal).save(os.path.join(dirs["item"], name + ".png"))

    # Added later: generated after the originals so their pixels stay the same.
    paint_skin(trader_painter).save(os.path.join(dirs["entity"], "prisoner_trader.png"))
    for name, fn in {"job_desk_top": job_desk_top, "job_desk_side": job_desk_side, "detector": detector}.items():
        tex16(fn).save(os.path.join(dirs["block"], name + ".png"))
    for name, (rows, pal) in MORE_ITEMS.items():
        from_pixels(rows, pal).save(os.path.join(dirs["item"], name + ".png"))
    main_v3(root)
    main_v4(root)


# ---------------------------------------------------------------- 0.3: variety

SKIN_TONES = [(241, 204, 172), (222, 172, 132), (190, 136, 96), (146, 98, 64), (102, 68, 46), (76, 50, 36)]
HAIR_COLORS = [(28, 24, 22), (70, 46, 30), (120, 84, 50), (200, 168, 96), (170, 80, 40), (150, 150, 150)]
EYE_COLORS = [(70, 46, 30), (50, 90, 160), (60, 120, 70), (110, 110, 120), (40, 30, 24)]
INK = (40, 60, 110)


def person_head(face, x, y, look):
    """A head from a 'look' dict: skin, hair colour and style, eyes, beard, scar, teardrop tattoo."""
    skin = look["skin"]
    dark = shade(skin, 0.86)[:3]
    hair = look["hair"]
    style = look["style"]
    beard = look["beard"]
    if style == "beanie":
        cap = look["cap"]
        if face == "top" or (face != "bottom" and y < 2):
            return shade(cap, 0.85)[:3] if (x + (y if face != "top" else 0)) % 2 else cap
    if face == "top":
        if style == "bald":
            return shade(skin, 1.04)[:3]
        if style == "mohawk":
            return hair if 3 <= x <= 4 else dark
        if style == "buzz":
            return hair if (x + y) % 2 else shade(hair, 1.25)[:3]
        return hair
    if face == "bottom":
        return hair if beard == "full" else dark
    hair_rows = {"bald": 0, "buzz": 1, "short": 2, "long": 2, "mohawk": 0, "beanie": 2}[style]
    if face == "back":
        if style == "long" and y < 7:
            return hair
        if style == "mohawk":
            return hair if 3 <= x <= 4 and y < 5 else dark
        return hair if y < hair_rows + 2 and style != "bald" else dark
    if face in ("left", "right"):
        if style == "long" and y < 7 and (x < 6 if face == "right" else x > 1):
            return hair
        if y < hair_rows and style != "mohawk":
            return hair
        if beard == "full" and y >= 5 and (x < 4 if face == "left" else x > 3):
            return hair
        if y == 4 and x in (3, 4):
            return dark
        return skin
    # front
    if style == "mohawk" and y == 0 and 3 <= x <= 4:
        return hair
    if y < hair_rows and style != "mohawk":
        return hair if style != "buzz" or (x % 2 == 0) else skin
    if style == "long" and x in (0, 7) and y < 6:
        return hair
    brow = hair if style not in ("bald", "beanie") else shade(skin, 0.6)[:3]
    if style == "beanie":
        brow = look["hair"]
    if y == 3 and (1 <= x <= 2 or 5 <= x <= 6):
        return brow
    if y == 4:
        if x in (1, 6):
            return (240, 240, 240)
        if x in (2, 5):
            return look["eyes"]
    if look["scar"] and x == 6 and 2 <= y <= 5 and y != 4:
        return (170, 90, 90)
    if look["tear"] and x == 1 and y == 5:
        return INK
    if y == 5 and 3 <= x <= 4:
        return dark  # nose
    if beard == "mustache" and y == 6 and 2 <= x <= 5:
        return hair
    if beard == "goatee" and y >= 6 and 3 <= x <= 4:
        return hair if y == 7 else (150, 90, 70)
    if beard == "full" and y >= 5 and not (y == 6 and 3 <= x <= 4):
        return hair if (x + y) % 3 else shade(hair, 1.2)[:3]
    if beard == "stubble" and y >= 6 and (x + y) % 2 == 0:
        return shade(skin, 0.7)[:3]
    if y == 6 and 3 <= x <= 4:
        return (150, 90, 70)  # mouth
    return skin


def make_look(r):
    return {
        "skin": r.choice(SKIN_TONES),
        "hair": r.choice(HAIR_COLORS),
        "style": r.choice(["bald", "buzz", "short", "long", "mohawk", "beanie", "short", "buzz"]),
        "cap": r.choice([(60, 60, 64), (40, 70, 120), (120, 40, 40), (60, 90, 50)]),
        "eyes": r.choice(EYE_COLORS),
        "beard": r.choice(["none", "none", "stubble", "full", "mustache", "goatee"]),
        "scar": r.random() < 0.25,
        "tear": r.random() < 0.2,
        "tattoo": r.choice(["none", "sleeve", "sleeve", "bands", "dots"]),
        "neck": r.random() < 0.25,
    }


def tattoo_px(kind, part, face, x, y):
    if kind == "sleeve":
        return (x * 3 + y * 2) % 5 == 0 or (part == "right_arm" and (x + y) % 4 == 0)
    if kind == "bands":
        return y in (5, 8)
    if kind == "dots":
        return (x + y) % 3 == 0 and y % 2 == 0
    return False


def inmate_painter(look):
    def paint(part, face, x, y, w, h):
        if part == "head":
            return person_head(face, x, y, look)
        if part in ("right_arm", "left_arm") and face != "top" and y >= 4:
            if face != "bottom" and y < 10 and look["tattoo"] != "none" and tattoo_px(look["tattoo"], part, face, x, y):
                return INK
            return look["skin"]
        if part == "body" and look["neck"] and face == "front" and y == 0 and x in (1, 6):
            return INK
        if part == "body" and face == "front" and y == 0 and 2 <= x <= 5:
            return (236, 236, 236)
        return prisoner_painter(part, face, x, y, w, h)
    return paint


def inmate_trader_painter(look):
    base = inmate_painter(look)

    def paint(part, face, x, y, w, h):
        if part == "head" and (face == "top" or y < 2):
            return (40, 140, 60) if (x + y) % 3 else (30, 110, 45)
        if part == "body" and face == "front" and y == 1 and 1 <= x <= 6:
            return (230, 190, 60)
        return base(part, face, x, y, w, h)
    return paint


def guard_variant_painter(look, shades):
    def paint(part, face, x, y, w, h):
        if part == "head":
            if face == "front" and y in (2, 3, 4, 5, 6, 7) or face in ("left", "right", "back", "bottom") and y >= 3:
                c = guard_painter(part, face, x, y, w, h)
                if c == SKIN or c == SKIN_DARK:
                    if face == "front" and not shades and y == 4 and x in (1, 2, 5, 6):
                        return (240, 240, 240) if x in (1, 6) else look["eyes"]
                    if face == "front" and look["beard"] == "mustache" and y == 6 and 2 <= x <= 5:
                        return look["hair"]
                    return look["skin"] if c == SKIN else shade(look["skin"], 0.86)[:3]
                if face == "front" and not shades and y == 4:
                    return (240, 240, 240) if x in (1, 6) else (look["eyes"] if x in (2, 5) else look["skin"])
                if face == "front" and y == 3 and not shades:
                    return look["hair"] if (1 <= x <= 2 or 5 <= x <= 6) else look["skin"]
                if c == (150, 90, 70) and look["beard"] == "mustache":
                    return (150, 90, 70)
                return c
        c = guard_painter(part, face, x, y, w, h)
        if c == SKIN:
            return look["skin"]
        return c
    return paint


def cut_broken(img):
    """The same tool snapped in the middle of the handle: a gap and a darker tint."""
    src = img.load()
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    px = out.load()
    for y in range(16):
        for x in range(16):
            c = src[x, y]
            if c[3] == 0:
                continue
            d = x - y
            if d in (-4, -3):
                continue
            if d < -4:
                nx, ny = x - 1, y + 1
            else:
                nx, ny = x, y
            if 0 <= nx < 16 and 0 <= ny < 16:
                px[nx, ny] = shade(c, 0.8)
    return out


GRAFFITI = [
    [  # smiley
        "................", "................", ".....wwwwww.....", "....w......w....", "...w........w...",
        "...w..w..w..w...", "...w........w...", "...w........w...", "...w.w....w.w...", "...w..wwww..w...",
        "....w......w....", ".....wwwwww.....", "................", "................", "................", "................"],
    [  # heart with arrow
        "................", "................", "...rr....rr.....", "..rrrr..rrrr....", ".rrrrrrrrrrrr...",
        ".rrrrrrrrrrrr..w", ".rrrrrrrrrrrr.w.", "..rrrrrrrrrrw...", "...rrrrrrrww....", "....rrrrrw......",
        ".....rrww.......", "......ww........", "....ww..........", "..ww............", "................", "................"],
    [  # tally marks
        "................", "................", "................", "..w.w.w.w.......", "..w.w.w.w...w...",
        "..w.w.w.ww.w....", "..w.w.wwww......", "..w.w.ww.w......", "..w.ww.w.w......", "..www.w.w.......",
        "..ww.w.w.w......", ".ww.w.w.w.......", "................", "................", "................", "................"],
    [  # skull
        "................", "................", ".....wwwwww.....", "....wwwwwwww....", "...wwwwwwwwww...",
        "...ww..ww..ww...", "...ww..ww..ww...", "...wwwwwwwwww...", "....wwww.www....", ".....wwwwww.....",
        ".....w.w.w.w....", ".....wwwwww.....", "................", "................", "................", "................"],
    [  # crown
        "................", "................", "................", "...y...y...y....", "...yy.yyy.yy....",
        "...yyyyyyyyy....", "...yyyyyyyyy....", "...yryyyyyry....", "...yyyyyyyyy....", "...yyyyyyyyy....",
        "................", "................", "................", "................", "................", "................"],
    [  # anarchy A in a circle
        "................", ".....wwwwww.....", "....w...w..w....", "...w...w.w..w...", "..w...w...w..w..",
        "..w..w.....w.w..", "..w.wwwwwwwww.w..", "..w.w.......ww...", "..ww.........w..", "..w..........w..",
        "...w........w...", "....w......w....", ".....wwwwww.....", "................", "................", "................"],
]
GRAFFITI_PAL = {"w": (236, 236, 228), "r": (220, 60, 60), "y": (240, 200, 60)}


GRAFFITI += [
    [  # sun
        "................", ".......y........", "..y....y....y...", "...y...y...y....", "....yyyyyyy.....",
        "....y.....y.....", "....y.y.y.y.....", "yyyyy.....yyyyy.", "....y.yyy.y.....", "....y.....y.....",
        "....yyyyyyy.....", "...y...y...y....", "..y....y....y...", ".......y........", "................", "................"],
    [  # star
        "................", ".......w........", ".......w........", "......w.w.......", "......w.w.......",
        "wwwwww...wwwwww.", ".w...........w..", "..w.........w...", "...w.......w....", "...w...w...w....",
        "..w...w.w...w...", "..w..w...w..w...", ".w.ww.....ww.w..", ".ww.........ww..", "................", "................"],
    [  # cat face
        "................", "..w.........w...", "..ww.......ww...", "..w.w.....w.w...", "..w..wwwww..w...",
        "..w.........w...", ".w..gg...gg..w..", ".w...........w..", ".w.....r.....w..", "ww.w..w.w..w.ww.",
        ".w...w...w...w..", "..w.........w...", "...wwwwwwwww....", "................", "................", "................"],
    [  # fish
        "................", "................", "................", ".....wwwww......", "...ww.....ww..w.",
        "..w.........w.ww", ".w..w........w.w", "w............ww.", ".w..........w.ww", "..w.........w.ww",
        "...ww.....ww..w.", ".....wwwww......", "................", "................", "................", "................"],
    [  # key
        "................", "................", "...yyy..........", "..y...y.........", ".y.....y........",
        ".y.....yyyyyyyyy", ".y.....y....y.y.", "..y...y.....y.y.", "...yyy......y...", "................",
        "................", "................", "................", "................", "................", "................"],
    [  # eye
        "................", "................", "................", "....wwwwwww.....", "..ww.......ww...",
        ".w....bbb....w..", "w....bbbbb....w.", "w....bbwbb....w.", "w....bbbbb....w.", ".w....bbb....w..",
        "..ww.......ww...", "....wwwwwww.....", "................", "................", "................", "................"],
    [  # flower
        "................", "......rrr.......", ".....r...r......", ".rrr.r.y.r.rrr..", "r...rr...rr...r.",
        "r.....yyy.....r.", ".r...y...y...r..", "..rrr.y.y.rrr...", "......ggg.......", ".......g........",
        ".gg....g....gg..", "..ggg..g..ggg...", "....ggggggg.....", ".......g........", ".......g........", "................"],
    [  # stick figure behind bars
        "................", ".w..w..w..w..w..", ".w..w.www..w..w.", ".w..w.w.w..w..w.", ".w..w.www..w..w.",
        ".w..w..w..w..w..", ".w..wwwwwww..w..", ".w..w..w..w..w..", ".w..w..w..w..w..", ".w..w.w.w..w..w.",
        ".w..ww...ww..w..", ".w..w..w..w..w..", ".wwwwwwwwwwwwww.", ".w..w..w..w..w..", "................", "................"],
    [  # house
        "................", ".......w........", "......w.w.......", ".....w...w......", "....w.....w.....",
        "...w.......w....", "..wwwwwwwwwww...", "..w.........w...", "..w..yy.....w...", "..w..yy..ww.w...",
        "..w.......w.w...", "..w.......w.w...", "..wwwwwwwwwww...", "................", "................", "................"],
    [  # lightning
        "................", "........yyyy....", ".......yyyy.....", "......yyyy......", ".....yyyy.......",
        "....yyyyyyyy....", ".......yyyy.....", "......yyyy......", ".....yyyy.......", "....yyy.........",
        "...yy...........", "..y.............", "................", "................", "................", "................"],
]
GRAFFITI_PAL.update({"g": (90, 200, 90), "b": (80, 140, 230)})


def graffiti_img(rows):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                c = GRAFFITI_PAL[ch]
                px[x, y] = jitter(c, 12)[:3] + (rng.randint(190, 240),)
    return img


def detector_side(x, y):
    if x in (0, 15):
        return jitter((92, 96, 104), 3)
    if y % 5 == 0:
        return jitter((70, 74, 80), 3)
    return jitter((168, 172, 180), 5)


def detector_panel(x, y):
    if y % 3 == 0 and 2 <= x <= 13:
        return (80, 220, 120, 255)
    return jitter((24, 28, 30), 3)


def lamp(color):
    def f(x, y):
        k = 1.25 if 5 <= x <= 10 and 5 <= y <= 10 else 1.0
        return jitter(shade(color, k), 6)
    return f


NEW_ITEMS = {
    "work_pickaxe": ([
        "................",
        "...ooooooooo....",
        "..osssssssssoo..",
        ".osllllooollso..",
        "..oo..ohyo..oo..",
        "......ohyo......",
        ".....ohyo.......",
        ".....ohyo.......",
        "....ohyo........",
        "....owwo........",
        "...owwo.........",
        "...owwo.........",
        "..owwo..........",
        "..owwo..........",
        "..ooo...........",
        "................",
    ], {"o": OUT, "s": (190, 194, 202), "l": (120, 124, 132), "h": (150, 100, 60), "y": (230, 190, 40), "w": (150, 100, 60)}),
    "work_axe": ([
        "................",
        "........oooo....",
        ".......osssso...",
        "......osssllso..",
        "......osslllso..",
        ".......ohslso...",
        "......ohwooo....",
        ".....ohwo.......",
        "....ohwo........",
        "....oywo........",
        "...oywo.........",
        "...owwo.........",
        "..owwo..........",
        "..owwo..........",
        "..ooo...........",
        "................",
    ], {"o": OUT, "s": (190, 194, 202), "l": (120, 124, 132), "h": (110, 70, 40), "y": (230, 190, 40), "w": (150, 100, 60)}),
    "chalk": ([
        "................",
        "................",
        "................",
        "...........oo...",
        "..........owwo..",
        ".........owwwo..",
        "........owwwo...",
        ".......owwwo....",
        "......owwwo.....",
        ".....owwwo......",
        "....owwwo.......",
        "...ogwwo........",
        "...oggo.........",
        "....oo..........",
        "................",
        "................",
    ], {"o": (90, 90, 96), "w": (240, 240, 232), "g": (200, 200, 190)}),
}


def outlined(cells, palette):
    """16x16 item from {(x, y): colour key}; every empty pixel touching the shape becomes outline."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for (x, y), k in cells.items():
        px[x, y] = palette[k] + (255,)
    for y in range(16):
        for x in range(16):
            if (x, y) in cells:
                continue
            if any((x + dx, y + dy) in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = OUT + (255,)
    return img


WORK_PAL = {"s": (226, 230, 236), "l": (168, 174, 184), "d": (104, 110, 122), "w": (150, 102, 58),
            "h": (104, 68, 38), "y": (236, 196, 44), "k": (40, 40, 44)}


def work_handle(cells, top):
    """A two-tone wooden handle from the bottom-left corner up to `top`, with yellow-black tape."""
    for t in range(top):
        x, y = 2 + t, 13 - t
        tape = t in (2, 3)
        cells[(x, y)] = ("y" if t == 2 else "k") if tape else "w"
        cells[(x + 1, y)] = ("k" if t == 2 else "y") if tape else "h"


def work_pickaxe_img():
    import math
    cells = {}
    work_handle(cells, 9)
    # The head: an arc round the bottom-left corner, from the top-left over to the right.
    cx, cy = 3.0, 13.0
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - cx, cy - (y + 0.5)
            r = math.hypot(dx, dy)
            ang = math.degrees(math.atan2(dy, dx))
            if 8.7 <= r <= 10.6 and -8 <= ang <= 98:
                ends = ang < 2 or ang > 88
                cells[(x, y)] = "d" if ends else ("s" if r > 9.7 else "l")
    return outlined(cells, WORK_PAL)


def work_axe_img():
    cells = {}
    work_handle(cells, 11)
    # The blade on the upper-left of the handle's top; the cutting edge is bright.
    rows = {1: (8, 9), 2: (6, 10), 3: (5, 10), 4: (5, 10), 5: (5, 9), 6: (6, 8), 7: (7, 7)}
    for y, (x0, x1) in rows.items():
        for x in range(x0, x1 + 1):
            if (x, y) in cells:
                continue
            cells[(x, y)] = "s" if x == x0 else ("d" if x >= x1 - 0 and y > 1 else "l")
    # A short back spike on the other side.
    cells[(14, 3)] = "l"
    cells[(14, 4)] = "d"
    return outlined(cells, WORK_PAL)


def main_v3(root):
    dirs = {k: os.path.join(root, "textures", k) for k in ("entity", "block", "item")}
    for i in range(16):
        look = make_look(random.Random(5000 + i))
        paint_skin(inmate_painter(look)).save(os.path.join(dirs["entity"], "prisoner_%d.png" % i))
        paint_skin(inmate_trader_painter(look)).save(os.path.join(dirs["entity"], "prisoner_trader_%d.png" % i))
    for i in range(6):
        r = random.Random(7000 + i)
        look = make_look(r)
        look["beard"] = r.choice(["none", "mustache"])
        paint_skin(guard_variant_painter(look, shades=i % 2 == 0)).save(os.path.join(dirs["entity"], "guard_%d.png" % i))
    for name in ("makeshift_pickaxe", "makeshift_shovel", "file"):
        img = Image.open(os.path.join(dirs["item"], name + ".png")).convert("RGBA")
        target = {"makeshift_pickaxe": "broken_pickaxe", "makeshift_shovel": "broken_shovel", "file": "broken_file"}[name]
        cut_broken(img).save(os.path.join(dirs["item"], target + ".png"))
    for name, (rows, pal) in NEW_ITEMS.items():
        from_pixels(rows, pal).save(os.path.join(dirs["item"], name + ".png"))
    work_pickaxe_img().save(os.path.join(dirs["item"], "work_pickaxe.png"))
    work_axe_img().save(os.path.join(dirs["item"], "work_axe.png"))
    for i, rows in enumerate(GRAFFITI[:6]):
        graffiti_img(rows).save(os.path.join(dirs["block"], "graffiti_%d.png" % i))
    for name, fn in {"detector_side": detector_side, "detector_panel": detector_panel,
                     "detector_lamp_off": lamp((60, 62, 66)), "detector_lamp_green": lamp((60, 210, 90)),
                     "detector_lamp_red": lamp((230, 50, 40))}.items():
        tex16(fn).save(os.path.join(dirs["block"], name + ".png"))
    # 0.3.2: more drawings, after everything else so the older textures keep their pixels.
    for i, rows in enumerate(GRAFFITI[6:], 6):
        graffiti_img(rows).save(os.path.join(dirs["block"], "graffiti_%d.png" % i))

def cook_painter(look, cheerful):
    white = (236, 236, 232)
    white_d = (210, 210, 206)
    check = (60, 60, 70) if not cheerful else (200, 60, 60)

    def paint(part, face, x, y, w, h):
        if part == "head":
            if face == "top" or y < 2:
                return white if (x + y) % 3 else white_d  # chef's hat
            c = person_head(face, x, y, look)
            return c
        if part == "body":
            if face == "front" and 3 <= x <= 4 and y in (2, 5, 8):
                return (60, 60, 64)  # buttons
            if face == "front" and y >= 6:
                return (246, 246, 244) if x not in (0, 7) else white_d  # apron
            if face == "front" and y == 0 and 2 <= x <= 5:
                return check  # neckerchief
            return white if y % 4 else white_d
        if part in ("right_arm", "left_arm"):
            if face == "bottom" or y >= 8:
                return look["skin"]
            return white
        if face == "bottom" or y >= 11:
            return (40, 40, 44)
        return check if (x + y) % 2 else shade(check, 0.8)[:3]  # checked trousers
    return paint


RAG = ([
    "................",
    "................",
    "....oooooo......",
    "...okkkkkko.....",
    "..okkdkkkkkoo...",
    "..okkkkkdkkkko..",
    ".okdkkkkkkkdko..",
    ".okkkkkdkkkkko..",
    ".okkkkkkkkkkko..",
    "..okkdkkkkdkko..",
    "..okkkkkkkkko...",
    "...ooookkkko....",
    ".......oooo.....",
    "................",
    "................",
    "................",
], {"o": (60, 56, 50), "k": (196, 186, 160), "d": (150, 140, 116)})


def main_v4(root):
    dirs = {k: os.path.join(root, "textures", k) for k in ("entity", "block", "item")}
    gloomy = make_look(random.Random(9001))
    gloomy.update({"style": "short", "beard": "mustache", "hair": (150, 150, 150)})
    cheerful = make_look(random.Random(9002))
    cheerful.update({"style": "short", "beard": "none", "scar": False, "tear": False})
    paint_skin(cook_painter(gloomy, False)).save(os.path.join(dirs["entity"], "cook_0.png"))
    paint_skin(cook_painter(cheerful, True)).save(os.path.join(dirs["entity"], "cook_1.png"))
    from_pixels(*RAG).save(os.path.join(dirs["item"], "rag.png"))


if __name__ == "__main__":
    main()
