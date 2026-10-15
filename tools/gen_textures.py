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
        ".............oo.",
        "............owo.",
        "...........owo..",
        "..........owo...",
        ".........owo....",
        "........owo.....",
        "...ooooowo......",
        "..ossssoo.......",
        ".osssssso.......",
        ".osssssso.......",
        ".ossssso........",
        ".osssso.........",
        "..oooo..........",
        "................",
        "................",
    ], {"o": OUT, "s": (190, 190, 196), "w": (176, 128, 80)}),
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


if __name__ == "__main__":
    main()
