"""Cellbreak textures, 0.5.1: the prison look in vanilla style (16x16).

Usage: python3 gen_textures_v9.py <assets_dir>

Replaces the 64x64 realistic materials of 0.5.0 (gen_textures_v8.py) with 16x16 pixel art in the
manner of vanilla Minecraft: a few tones per material, pixel noise, light upper-left edges and dark
lower-right edges on every brick, tile and panel. Block models and their UVs stay the same.
"""
import math
import os
import random
import sys

from PIL import Image

S = 16


def clamp(v):
    return max(0, min(255, int(round(v))))


def tone(c, k):
    return tuple(clamp(v * k) for v in c[:3])


class Tex:
    def __init__(self, base=(128, 128, 128), seed=0):
        self.img = Image.new("RGBA", (S, S), base + (255,))
        self.px = self.img.load()
        self.rnd = random.Random(seed)

    def set(self, x, y, c, a=255):
        self.px[x % S, y % S] = tuple(clamp(v) for v in c[:3]) + (a,)

    def get(self, x, y):
        return self.px[x % S, y % S][:3]

    def fill_noise(self, base, levels=(0.88, 0.94, 1.0, 1.0, 1.06), mask=None):
        for y in range(S):
            for x in range(S):
                if mask is None or mask(x, y):
                    self.set(x, y, tone(base, self.rnd.choice(levels)))

    def shade(self, x, y, k):
        self.set(x, y, tone(self.get(x, y), k))

    def speckle(self, n, k, mask=None):
        for _ in range(n):
            x, y = self.rnd.randrange(S), self.rnd.randrange(S)
            if mask is None or mask(x, y):
                self.shade(x, y, k)


def bricks(base, mortar, seed, course=4, unit=8, offset=4):
    """Running-bond bricks/blocks like vanilla stone bricks: bevelled edges, a mortar line."""
    t = Tex(seed=seed)
    t.fill_noise(base)
    for row in range(S // course):
        y0 = row * course
        off = offset if row % 2 else 0
        for y in range(y0, y0 + course):
            for x in range(S):
                lx = (x + off) % unit
                ly = y - y0
                if ly == course - 1 or lx == unit - 1:
                    t.set(x, y, tone(mortar, t.rnd.choice((0.95, 1.0, 1.0))))
                elif ly == 0 or lx == 0:
                    t.shade(x, y, 1.08)
                elif ly == course - 2 or lx == unit - 2:
                    t.shade(x, y, 0.9)
    return t


def cracks(t, seed, colour=(52, 50, 48)):
    rnd = random.Random(seed)
    for _ in range(2):
        x, y = rnd.randrange(3, 13), rnd.randrange(0, 4)
        for _ in range(14):
            t.set(x, y, colour)
            if rnd.random() < 0.3:
                t.set(x + 1, y, tone(colour, 1.5))
            x += rnd.choice((-1, 0, 1))
            y += 1
            if y >= S:
                break
    return t


# ---------------------------------------------------------------- building blocks

PAINT = (196, 191, 178)
BAND = (84, 100, 110)


def prison_wall(seed=1):
    t = bricks(PAINT, (150, 146, 136), seed)
    t.speckle(10, 0.9)
    return t


def prison_wall_band(seed=2):
    t = bricks(BAND, (60, 70, 78), seed)
    t.speckle(8, 0.85)
    # A worn yellow-grey line along the top of the band.
    for x in range(S):
        t.set(x, 0, tone((150, 140, 96), t.rnd.choice((0.95, 1.0, 1.05))))
    return t


def concrete(base, seed, levels=(0.9, 0.95, 1.0, 1.0, 1.04)):
    t = Tex(seed=seed)
    t.fill_noise(base, levels)
    t.speckle(12, 0.85)
    t.speckle(6, 1.1)
    return t


def concrete_wall(seed=3):
    t = concrete((160, 158, 152), seed)
    # Panel edges (vanilla-style bevel) and two form-tie holes.
    for i in range(S):
        t.shade(i, 0, 1.1)
        t.shade(0, i, 1.08)
        t.shade(i, S - 1, 0.78)
        t.shade(S - 1, i, 0.8)
    for x, y in ((4, 6), (11, 6), (4, 12), (11, 12)):
        t.set(x, y, (82, 80, 76))
    # A rain streak.
    x = t.rnd.randrange(2, 14)
    for y in range(1, 15):
        if t.rnd.random() < 0.7:
            t.shade(x, y, 0.92)
    return t


def concrete_column(seed=4):
    t = concrete((200, 196, 186), seed)
    for y in range(S):
        t.shade(0, y, 0.82)
        t.shade(1, y, 1.08)
        t.shade(14, y, 0.9)
        t.shade(15, y, 0.76)
    return t


def smooth_concrete(seed=5):
    return concrete((176, 174, 168), seed)


def prison_floor(seed=6):
    t = concrete((150, 150, 146), seed, (0.92, 0.96, 1.0, 1.0, 1.03))
    for i in range(S):
        t.shade(i, 0, 1.1)
        t.shade(0, i, 1.06)
        t.shade(i, S - 1, 0.8)
        t.shade(S - 1, i, 0.82)
    return t


def floor_tile(seed=7):
    t = Tex(seed=seed)
    base = (214, 212, 204)
    t.fill_noise(base, (0.96, 1.0, 1.0, 1.02))
    for y in range(S):
        for x in range(S):
            lx, ly = x % 8, y % 8
            if lx == 7 or ly == 7:
                t.set(x, y, tone((150, 148, 140), t.rnd.choice((0.95, 1.0))))
            elif lx == 0 or ly == 0:
                t.shade(x, y, 1.04)
            elif lx == 6 or ly == 6:
                t.shade(x, y, 0.94)
    t.speckle(5, 0.92)
    return t


def gym_floor(seed=8):
    t = Tex(seed=seed)
    t.fill_noise((44, 45, 48), (0.85, 0.95, 1.0, 1.05))
    for _ in range(18):
        x, y = t.rnd.randrange(S), t.rnd.randrange(S)
        t.set(x, y, t.rnd.choice(((96, 100, 108), (62, 96, 146), (140, 140, 140))))
    for i in range(S):
        t.shade(i, S - 1, 0.7)
        t.shade(S - 1, i, 0.7)
    return t


def asphalt(seed=9):
    t = concrete((72, 72, 74), seed, (0.85, 0.95, 1.0, 1.05, 1.12))
    return t


def roof_gravel(seed=10):
    t = Tex(seed=seed)
    t.fill_noise((126, 124, 118), (0.8, 0.9, 1.0, 1.1, 1.2))
    for _ in range(14):
        x, y = t.rnd.randrange(S), t.rnd.randrange(S)
        t.set(x, y, tone((160, 156, 148), 1.05))
        t.set(x, y + 1, tone((90, 88, 84), 1.0))
    return t


def dark_concrete(seed=11):
    t = concrete((98, 100, 102), seed)
    for i in range(S):
        t.shade(i, 0, 1.12)
        t.shade(0, i, 1.08)
        t.shade(i, S - 1, 0.8)
        t.shade(S - 1, i, 0.82)
    return t


def steel_panel(seed=12, rivets=True, base=(170, 174, 180)):
    t = Tex(seed=seed)
    for x in range(S):
        k = t.rnd.choice((0.94, 0.98, 1.0, 1.03))
        for y in range(S):
            t.set(x, y, tone(base, k * (1.0 - y * 0.006)))
    t.speckle(6, 0.92)
    if rivets:
        for i in range(S):
            t.shade(i, 0, 1.15)
            t.shade(0, i, 1.12)
            t.shade(i, S - 1, 0.72)
            t.shade(S - 1, i, 0.75)
        for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
            t.set(x, y, (222, 224, 228))
            t.set(x + 1, y + 1, (100, 102, 108))
    return t


def ceiling_tile(seed=13):
    t = Tex(seed=seed)
    t.fill_noise((224, 222, 214), (0.96, 1.0, 1.0, 1.02))
    t.speckle(14, 0.9)
    for i in range(S):
        t.set(i, S - 1, (176, 176, 172))
        t.set(S - 1, i, (176, 176, 172))
    return t


def light_panel():
    t = Tex()
    for y in range(S):
        for x in range(S):
            edge = x in (0, S - 1) or y in (0, S - 1)
            grid = x % 3 == 0 or y % 3 == 0
            t.set(x, y, (186, 188, 192) if edge else (236, 238, 236) if grid else (255, 255, 248))
    return t


def wired_glass():
    t = Tex()
    for y in range(S):
        for x in range(S):
            edge = x in (0, S - 1) or y in (0, S - 1)
            wire = (x + y) % 4 == 0 or (x - y) % 4 == 0
            if edge:
                t.set(x, y, (172, 196, 204), 255)
            elif wire:
                t.set(x, y, (150, 156, 160), 200)
            else:
                t.set(x, y, (210, 230, 236), 60)
    for i in range(2, 5):
        t.set(i + 1, i, (240, 250, 252), 140)
    return t


def chain_link():
    t = Tex()
    for y in range(S):
        for x in range(S):
            a, b = (x + y) % 4, (x - y) % 4
            if a == 0 and b == 0:
                t.set(x, y, (206, 210, 214))
            elif a == 0 or b == 0:
                t.set(x, y, (156, 160, 164))
            else:
                t.set(x, y, (0, 0, 0), 0)
    for x in range(S):
        t.set(x, 0, (190, 194, 198))
    return t


def chain_link_edge():
    t = Tex()
    for y in range(S):
        for x in range(S):
            t.set(x, y, (0, 0, 0), 0)
        t.set(7, y, (186, 190, 194))
        t.set(8, y, (140, 144, 148))
    return t


def razor_wire():
    t = Tex()
    for y in range(S):
        for x in range(S):
            t.set(x, y, (0, 0, 0), 0)
    for cx in (3, 11):
        for a in range(0, 360, 20):
            x = int(round(cx + 3 * math.cos(math.radians(a))))
            y = int(round(9 + 5 * math.sin(math.radians(a))))
            if 0 <= y < S:
                t.set(x, y, (200, 204, 210) if math.sin(math.radians(a)) < 0 else (130, 134, 140))
    for x, y in ((3, 3), (11, 3), (7, 9), (15, 9), (3, 14), (11, 14)):
        t.set(x, y, (230, 232, 236))
    return t


def cell_door(top):
    t = Tex(seed=20 if top else 21)
    paint = (90, 104, 114)
    t.fill_noise(paint, (0.94, 0.98, 1.0, 1.03))
    for y in range(S):
        t.shade(0, y, 0.75)
        t.shade(S - 1, y, 0.72)
        t.shade(1, y, 1.08)
    if top:
        for x in range(S):
            t.shade(x, 0, 0.75)
        for y in range(3, 11):
            for x in range(5, 11):
                wire = (x + y) % 2 == 0
                t.set(x, y, (64, 76, 84) if wire else (36, 44, 50))
        for x in range(4, 12):
            t.set(x, 2, (150, 160, 166))
            t.set(x, 11, (54, 62, 68))
        for y in range(2, 12):
            t.set(4, y, (150, 160, 166))
            t.set(11, y, (54, 62, 68))
        for x in range(6, 10):
            t.set(x, 13, (218, 214, 198))
        t.set(1, 4, (60, 64, 68))
        t.set(1, 5, (60, 64, 68))
    else:
        for x in range(S):
            t.shade(x, S - 1, 0.7)
        for x in range(4, 12):
            t.set(x, 2, (150, 160, 166))
            t.set(x, 5, (52, 60, 66))
            for y in (3, 4):
                t.shade(x, y, 0.82)
        for y in range(12, 15):
            for x in range(1, 15):
                t.set(x, y, tone((150, 154, 158), t.rnd.choice((0.95, 1.0, 1.05))))
        for x in range(1, 15):
            t.set(x, 12, (188, 192, 196))
        t.set(13, 1, (40, 42, 46))
        t.set(13, 2, (70, 74, 78))
        t.set(1, 9, (60, 64, 68))
        t.set(1, 10, (60, 64, 68))
    return t


def cell_door_item():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.paste(cell_door(True).img.resize((8, 8), Image.NEAREST), (4, 0))
    img.paste(cell_door(False).img.resize((8, 8), Image.NEAREST), (4, 8))
    return img


# ---------------------------------------------------------------- model materials

def flat(base, seed, levels=(0.92, 0.97, 1.0, 1.0, 1.04)):
    t = Tex(seed=seed)
    t.fill_noise(base, levels)
    return t


def chrome():
    t = Tex(seed=31)
    for y in range(S):
        k = 0.7 + 0.5 * (math.sin(y / S * math.pi * 2 + 0.6) + 1) / 2
        for x in range(S):
            t.set(x, y, tone((196, 200, 206), k * t.rnd.choice((0.97, 1.0, 1.03))))
    return t


def leather(base, seed):
    t = flat(base, seed, (0.9, 0.96, 1.0, 1.04))
    for i in range(1, S - 1):
        if i % 2:
            for x, y in ((i, 1), (i, S - 2), (1, i), (S - 2, i)):
                t.set(x, y, tone(base, 1.8))
    for i in range(S):
        t.shade(i, S - 1, 0.7)
        t.shade(S - 1, i, 0.75)
    return t


def belt():
    t = flat((38, 40, 42), 32)
    for y in range(0, S, 3):
        for x in range(S):
            t.shade(x, y, 0.75)
    return t


def console():
    t = flat((42, 44, 48), 33)
    for y in range(2, 7):
        for x in range(3, 13):
            t.set(x, y, (40, 74, 62))
    for x in range(4, 11, 2):
        t.set(x, 3, (130, 236, 176))
        t.set(x + 1, 5, (130, 236, 176))
    for i, c in enumerate(((220, 60, 50), (60, 180, 80), (230, 200, 60), (80, 140, 220))):
        t.set(3 + i * 3, 10, c)
        t.set(4 + i * 3, 10, c)
    for x in range(4, 12):
        t.set(x, 13, (74, 76, 82))
    return t


def plate():
    t = Tex(seed=34)
    for y in range(S):
        for x in range(S):
            r = math.hypot(x - 7.5, y - 7.5)
            if r > 7.8:
                t.set(x, y, (0, 0, 0), 0)
            elif r > 6.5:
                t.set(x, y, (58, 58, 62))
            elif r > 3:
                t.set(x, y, tone((36, 36, 38), t.rnd.choice((0.9, 1.0, 1.1))))
            elif r > 1.6:
                t.set(x, y, (178, 182, 188))
            else:
                t.set(x, y, (30, 30, 32))
    t.set(6, 4, (230, 230, 230))
    t.set(7, 4, (230, 230, 230))
    t.set(9, 4, (230, 230, 230))
    t.set(7, 11, (200, 50, 40))
    t.set(8, 11, (200, 50, 40))
    return t


def bag():
    t = flat((150, 30, 32), 35, (0.88, 0.95, 1.0, 1.05))
    for x in range(S):
        t.shade(x, 0, 0.8)
        for y in (2, 3, 12, 13):
            t.set(x, y, tone((32, 32, 34), t.rnd.choice((0.9, 1.0, 1.1))))
    for y in range(6, 10):
        for x in range(5, 11):
            t.set(x, y, (232, 228, 220))
    for x, y in ((6, 7), (7, 7), (8, 8), (9, 7)):
        t.set(x, y, (150, 30, 32))
    for y in range(S):
        t.shade(0, y, 0.8)
        t.shade(S - 1, y, 0.8)
    return t


def lens():
    t = flat((30, 32, 36), 36)
    for y in range(S):
        for x in range(S):
            r = math.hypot(x - 7.5, y - 7.5)
            if r < 6:
                t.set(x, y, (74, 76, 84) if r > 4.6 else (16, 20, 34) if r > 2.2 else (44, 64, 126))
    t.set(6, 6, (210, 220, 236))
    return t


def feed(seed, scene):
    t = Tex(seed=seed)
    for y in range(S):
        for x in range(S):
            g = 70
            if scene == 0:
                g += 40 if abs(x - 8) < (y - 3) * 0.9 else 0
            elif scene == 1:
                g += 30 if y > 9 else (-20 if (x + y) % 3 == 0 else 0)
            else:
                g += -35 if (x % 4 == 1 and 4 < y < 12) else (25 if y > 12 else 0)
            g += t.rnd.randint(-10, 10)
            if y % 2:
                g *= 0.88
            t.set(x, y, (g * 0.9, g, g * 0.92))
    for x in range(1, 7):
        t.set(x, 1, (220, 220, 220) if x % 2 else (90, 90, 90))
    t.set(14, 1, (230, 40, 40))
    return t


def mirror():
    t = Tex()
    for y in range(S):
        for x in range(S):
            k = (x + (S - y)) / (2 * S)
            t.set(x, y, (170 + 40 * k, 192 + 36 * k, 204 + 34 * k))
    for i in range(S):
        if 0 <= i - 3 < S:
            t.set(i, i - 3, (238, 246, 250))
        if 0 <= i + 4 < S:
            t.set(i, i + 4, (226, 236, 240))
    return t


def hex_head():
    t = flat((36, 36, 38), 37)
    for i in range(S):
        t.shade(i, 0, 1.4)
        t.shade(i, S - 1, 0.7)
    return t


def grip():
    t = Tex()
    for y in range(S):
        for x in range(S):
            knurl = (x + y) % 2 == 0
            t.set(x, y, (126, 130, 136) if knurl else (168, 172, 178))
    return t


def keyboard():
    t = flat((30, 30, 32), 38)
    for y in range(S):
        for x in range(S):
            if x % 2 == 1 and y % 3 != 0:
                t.set(x, y, (78, 80, 86))
    return t


def led():
    t = Tex()
    for y in range(S):
        for x in range(S):
            t.set(x, y, (255, 50, 36))
    return t


# ---------------------------------------------------------------- 0.5.2: furniture and fittings

def panel(base, seed, bevel=True):
    t = flat(base, seed, (0.95, 0.98, 1.0, 1.0, 1.03))
    if bevel:
        for i in range(S):
            t.shade(i, 0, 1.12)
            t.shade(0, i, 1.08)
            t.shade(i, S - 1, 0.72)
            t.shade(S - 1, i, 0.75)
    return t


def handle(t, x0, x1, y, c=(214, 216, 220)):
    for x in range(x0, x1 + 1):
        t.set(x, y, c)
    t.set(x0, y + 1, tone(c, 0.6))
    t.set(x1, y + 1, tone(c, 0.6))


def cabinet_front():
    t = panel((150, 156, 162), 60)
    for y0 in (2, 9):
        for x in range(2, 14):
            t.set(x, y0, (110, 116, 122))
            t.set(x, y0 + 5, (186, 190, 196))
        for y in range(y0, y0 + 6):
            t.set(2, y, (110, 116, 122))
            t.set(13, y, (186, 190, 196))
        handle(t, 6, 9, y0 + 2)
    return t


def fridge_front():
    t = panel((196, 200, 204), 61)
    for y in range(2, 14):
        t.set(12, y, (230, 232, 236))
        t.set(13, y, (120, 124, 130))
    for x in range(3, 8):
        t.set(x, 3, (40, 60, 70))
        t.set(x, 4, (40, 60, 70))
    t.set(4, 3, (120, 230, 200))
    t.set(5, 3, (120, 230, 200))
    for x in range(1, 15):
        t.set(x, 7, (140, 144, 150))
    return t


def tool_front():
    t = panel((176, 40, 36), 62)
    for y0 in (2, 6, 10):
        for x in range(1, 15):
            t.set(x, y0 + 3, (110, 22, 20))
        handle(t, 5, 10, y0 + 1, (200, 204, 210))
    return t


def locker_front():
    t = panel((92, 108, 128), 63)
    for y in (2, 4, 6):
        for x in range(4, 12):
            t.set(x, y, (40, 46, 54))
    for y in range(9, 13):
        t.set(12, y, (200, 204, 210))
    t.set(11, 10, (60, 64, 70))
    for x in range(4, 9):
        t.set(x, 13, (222, 218, 200))
    return t


def hazard():
    t = Tex(seed=64)
    for y in range(S):
        for x in range(S):
            band = ((x + y) // 4) % 2 == 0
            c = (222, 186, 40) if band else (34, 32, 30)
            t.set(x, y, tone(c, t.rnd.choice((0.94, 1.0, 1.04))))
    for i in range(S):
        t.shade(i, 0, 1.1)
        t.shade(i, S - 1, 0.75)
    return t


def steel_ladder():
    t = Tex()
    for y in range(S):
        for x in range(S):
            t.set(x, y, (0, 0, 0), 0)
    for y in range(S):
        for x in (2, 13):
            t.set(x, y, (196, 200, 206))
            t.set(x + 1, y, (120, 124, 130))
    for y in (1, 5, 9, 13):
        for x in range(4, 13):
            t.set(x, y, (176, 180, 186))
            t.set(x, y + 1, (96, 100, 106))
    return t


def stripes(base, seed, stripe):
    t = flat(base, seed, (0.94, 1.0, 1.0, 1.04))
    for y in range(S):
        if y % 4 == 1:
            for x in range(S):
                t.set(x, y, tone(stripe, t.rnd.choice((0.95, 1.0))))
    return t


def blanket(base, seed):
    t = flat(base, seed, (0.86, 0.94, 1.0, 1.0, 1.06))
    for x in range(S):
        t.shade(x, 2, 0.8)
        t.shade(x, 13, 0.8)
    return t


def washer_front():
    t = panel((226, 228, 230), 65)
    for x in range(1, 15):
        t.set(x, 3, (170, 174, 180))
    for x, c in ((2, (60, 60, 64)), (3, (60, 60, 64)), (11, (220, 60, 50)), (12, (80, 200, 90))):
        t.set(x, 1, c)
    for y in range(S):
        for x in range(S):
            r = math.hypot(x - 7.5, y - 9.5)
            if 4.2 < r <= 5.4:
                t.set(x, y, (150, 156, 162))
            elif r <= 4.2:
                t.set(x, y, (70, 96, 120) if r > 2 or (x + y) % 3 else (180, 210, 230))
    return t


def clothes():
    t = flat((214, 104, 36), 66, (0.8, 0.9, 1.0, 1.1))
    for _ in range(8):
        x, y = t.rnd.randrange(S), t.rnd.randrange(S)
        t.set(x, y, (230, 230, 226))
    return t


def switch_box():
    t = panel((120, 124, 130), 67)
    t.set(7, 7, (226, 60, 50))
    return t


# ---------------------------------------------------------------- 0.5.3: name plates

def sign_plate():
    """64x32 sign sheet (vanilla layout): an aluminium plate with a dark rim and corner rivets."""
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = img.load()
    rnd = random.Random(90)
    base = (196, 200, 204)

    def plate(x0, y0, w, h, face):
        for y in range(h):
            for x in range(w):
                k = rnd.choice((0.96, 0.99, 1.0, 1.02))
                c = tone(base, k * (1.0 - y * 0.01))
                if face and (x in (0, w - 1) or y in (0, h - 1)):
                    c = (70, 78, 86)
                elif face and (x in (1, w - 2) or y in (1, h - 2)):
                    c = tone(base, 1.1 if (x == 1 or y == 1) else 0.8)
                px[x0 + x, y0 + y] = c + (255,)
        if face:
            for x, y in ((3, 3), (w - 4, 3), (3, h - 4), (w - 4, h - 4)):
                px[x0 + x, y0 + y] = (110, 116, 122, 255)

    plate(2, 2, 24, 12, True)    # front
    plate(28, 2, 24, 12, True)   # back
    plate(2, 0, 24, 2, False)    # top
    plate(26, 0, 24, 2, False)   # bottom
    plate(0, 2, 2, 12, False)    # side
    plate(26, 2, 2, 12, False)   # side
    for y in range(16, 30):      # post of the standing sign
        for x in range(0, 8):
            px[x, y] = tone((90, 96, 102), rnd.choice((0.9, 1.0, 1.1))) + (255,)
    return img


def sign_item():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    for y in range(3, 11):
        for x in range(1, 15):
            edge = x in (1, 14) or y in (3, 10)
            px[x, y] = ((70, 78, 86) if edge else (200, 204, 208)) + (255,)
    for x in range(4, 12):
        px[x, 6] = (40, 40, 44, 255)
    for x in range(5, 10):
        px[x, 8] = (40, 40, 44, 255)
    for y in range(11, 15):
        px[7, y] = (96, 100, 106, 255)
        px[8, y] = (70, 74, 80, 255)
    return img


def towels():
    t = flat((236, 236, 230), 91, (0.9, 0.96, 1.0, 1.0))
    for x in range(S):
        t.shade(x, 5, 0.85)
        t.shade(x, 11, 0.85)
    return t


# ---------------------------------------------------------------- 0.5.6: basketball hoop

def backboard():
    t = flat((240, 240, 236), 92, (0.97, 1.0, 1.0, 1.02))
    for i in range(S):
        for x, y in ((i, 0), (i, S - 1), (0, i), (S - 1, i)):
            t.set(x, y, (30, 30, 34))
    for i in range(5, 11):
        for x, y in ((i, 7), (i, 12), (5, i + 2), (10, i + 2)):
            if 7 <= y <= 12:
                t.set(x, y, (200, 40, 36))
    return t


def net():
    t = Tex()
    for y in range(S):
        for x in range(S):
            a, b = (x + y) % 4, (x - y) % 4
            t.set(x, y, (236, 236, 232) if a == 0 or b == 0 else (0, 0, 0), 255 if a == 0 or b == 0 else 0)
    return t


def main(root):
    block_dir = os.path.join(root, "textures", "block")
    item_dir = os.path.join(root, "textures", "item")
    textures = {
        "prison_wall": prison_wall(), "prison_wall_band": prison_wall_band(),
        "concrete_wall": concrete_wall(), "concrete_column": concrete_column(), "smooth_concrete": smooth_concrete(),
        "prison_floor": prison_floor(), "floor_tile": floor_tile(), "gym_floor": gym_floor(), "asphalt": asphalt(),
        "roof_gravel": roof_gravel(), "dark_concrete": dark_concrete(), "steel_panel": steel_panel(),
        "steel_table": steel_panel(14, rivets=False, base=(184, 188, 192)), "ceiling_tile": ceiling_tile(),
        "light_panel": light_panel(), "wired_glass": wired_glass(), "wired_glass_pane_top": wired_glass(),
        "chain_link": chain_link(), "chain_link_edge": chain_link_edge(), "razor_wire": razor_wire(),
        "cell_door_top": cell_door(True), "cell_door_bottom": cell_door(False),
        "mat_powder_black": flat((44, 46, 50), 40), "mat_chrome": chrome(), "mat_leather_black": leather((36, 34, 34), 41),
        "mat_leather_red": leather((128, 28, 30), 42), "mat_rubber": flat((32, 32, 34), 43), "mat_belt": belt(),
        "mat_console": console(), "mat_plate": plate(), "mat_bag": bag(), "mat_camera_body": flat((222, 224, 226), 44),
        "mat_lens": lens(), "mat_led": led(), "mat_feed_0": feed(51, 0), "mat_feed_1": feed(52, 1),
        "mat_feed_2": feed(53, 2), "mat_stainless": steel_panel(45, rivets=False, base=(186, 190, 194)),
        "mat_mirror": mirror(), "mat_hex": hex_head(), "mat_grip": grip(),
        "mat_toilet": steel_panel(46, rivets=False, base=(182, 186, 190)), "mat_water": flat((96, 140, 176), 47),
        "mat_screen_frame": flat((24, 24, 26), 48), "mat_keyboard": keyboard(),
    }
    textures.update({
        "cell_cabinet_front": cabinet_front(), "cell_cabinet_side": panel((150, 156, 162), 70),
        "cell_cabinet_top": panel((166, 170, 176), 71), "fridge_front": fridge_front(), "fridge_side": panel((196, 200, 204), 72),
        "tool_cabinet_front": tool_front(), "tool_cabinet_side": panel((176, 40, 36), 73), "tool_cabinet_top": flat((32, 32, 34), 74),
        "guard_locker_front": locker_front(), "guard_locker_side": panel((92, 108, 128), 75), "hazard_steel": hazard(),
        "steel_ladder": steel_ladder(), "mat_scrap": stripes((128, 132, 136), 76, (100, 104, 108)),
        "mat_scrap_lid": panel((112, 116, 120), 77), "mat_mattress": stripes((210, 210, 200), 78, (120, 140, 170)),
        "mat_pillow": flat((236, 236, 230), 79, (0.95, 1.0, 1.0)), "mat_blanket_gray": blanket((122, 124, 122), 80),
        "mat_blanket_blue": blanket((52, 74, 132), 81), "mat_blanket_white": blanket((230, 230, 226), 82),
        "washer_front": washer_front(), "washer_side": panel((226, 228, 230), 83), "mat_canvas": flat((150, 146, 132), 84),
        "mat_clothes": clothes(), "mat_switch": switch_box(),
    })
    textures["cracked_prison_wall"] = cracks(prison_wall(), 101)
    textures["cracked_prison_wall_band"] = cracks(prison_wall_band(), 102, (34, 40, 44))
    textures["cracked_concrete_wall"] = cracks(concrete_wall(), 103)
    for name, t in textures.items():
        t.img.save(os.path.join(block_dir, name + ".png"))
    cell_door_item().save(os.path.join(item_dir, "cell_door.png"))
    sign_dir = os.path.join(root, "textures", "entity", "signs")
    os.makedirs(sign_dir, exist_ok=True)
    sign_plate().save(os.path.join(sign_dir, "steel_plate.png"))
    sign_item().save(os.path.join(item_dir, "steel_sign.png"))
    towels().img.save(os.path.join(block_dir, "mat_towels.png"))
    backboard().img.save(os.path.join(block_dir, "mat_backboard.png"))
    net().img.save(os.path.join(block_dir, "mat_net.png"))
    flat((226, 108, 30), 93).img.save(os.path.join(block_dir, "mat_rim.png"))
    flat((40, 76, 150), 94, (0.9, 0.96, 1.0, 1.04)).img.save(os.path.join(block_dir, "mat_pad_blue.png"))


if __name__ == "__main__":
    main(sys.argv[1])
