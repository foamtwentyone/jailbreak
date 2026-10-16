"""Cellbreak textures, 0.4.0: the new items (dummy, gate key, rope with hook, tablet, parcel).

Usage: python3 gen_textures_v7.py <assets_dir>
"""
import math
import os
import random
import sys

from gen_textures_v6 import item, sh


def dummy():
    """A rolled blanket with a paper face: the bed dummy."""
    cells = {}
    cloth = (176, 168, 150)
    rnd = random.Random(11)
    for y in range(5, 14):
        for x in range(1, 12):
            if math.hypot((x - 6) / 5.4, (y - 9.5) / 4.2) <= 1.0:
                stripe = 1.08 if (x + 2 * y) % 6 < 2 else 1.0
                c = sh(cloth, stripe * (0.9 + 0.04 * (y - 5) / 2))
                if rnd.random() < 0.06:
                    c = (130, 116, 96)
                cells[(x, y)] = c
    face = (232, 226, 210)
    for y in range(3, 10):
        for x in range(9, 15):
            if math.hypot(x + 0.5 - 12, y + 0.5 - 6.5) < 3.0:
                cells[(x, y)] = face if x + y < 19 else sh(face, 0.85)
    cells[(11, 5)] = (40, 40, 44)
    cells[(13, 5)] = (40, 40, 44)
    for x in (11, 12, 13):
        cells[(x, 7)] = (150, 60, 50)  # a drawn smile
    cells[(12, 8)] = (150, 60, 50)
    for x, y in ((10, 3), (11, 2), (12, 2), (13, 3), (14, 4)):
        cells[(x, y)] = (92, 64, 40)  # string hair
    return item(cells)


def gate_key():
    """A big old brass key with a ring and a heavy bit."""
    cells = {}
    brass, brass_l, brass_d = (196, 154, 62), (240, 206, 112), (128, 94, 34)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x + 0.5 - 4.5, y + 0.5 - 4.5)
            if 1.8 <= r <= 3.7:
                cells[(x, y)] = brass_l if x + y < 8 else (brass_d if x + y > 11 else brass)
    for t in range(0, 9):
        x, y = 6 + t, 6 + t
        if x < 16 and y < 16:
            cells[(x, y)] = brass_l
            if x + 1 < 16:
                cells[(x + 1, y)] = brass_d
    for x, y in ((9, 13), (10, 14), (11, 12), (12, 13), (13, 11), (14, 12), (10, 13), (12, 12)):
        cells[(x, y)] = brass
    cells[(7, 7)] = (255, 236, 160)
    return item(cells)


def rope_hook():
    """A coil of rope with a three-pronged iron hook."""
    cells = {}
    rope, rope_d = (186, 150, 100), (132, 100, 62)
    for y in range(16):
        for x in range(16):
            r = math.hypot(x + 0.5 - 6, (y + 0.5 - 10) * 1.15)
            if 2.4 <= r <= 4.8:
                twist = (x + y) % 3 == 0
                cells[(x, y)] = rope_d if twist or r > 4.2 else rope
    for t in range(4):
        cells[(9 + t, 7 - t)] = rope if t % 2 else rope_d
    iron, iron_l, iron_d = (150, 152, 160), (210, 212, 220), (88, 90, 100)
    for y in range(1, 6):
        cells[(13, y)] = iron_l if y < 3 else iron
    for x, y in ((11, 1), (11, 2), (12, 3), (15, 1), (15, 2), (14, 3), (12, 5), (14, 5), (13, 6)):
        cells[(x, y)] = iron_d if y > 4 else iron
    return item(cells)


def tablet():
    """The guards' tablet: dark frame, a lit screen with a map and red dots."""
    cells = {}
    frame, frame_l = (44, 46, 54), (86, 90, 102)
    for y in range(2, 15):
        for x in range(2, 14):
            cells[(x, y)] = frame_l if x == 2 or y == 2 else frame
    rnd = random.Random(5)
    for y in range(4, 12):
        for x in range(4, 12):
            glow = 1.0 + 0.15 * (11 - y) / 8
            c = sh((40, 96, 150), glow)
            if x in (6, 9) or y in (6, 9):
                c = sh((90, 160, 210), glow)  # corridors on the map
            cells[(x, y)] = c
    for x, y in ((5, 5), (10, 7), (7, 10)):
        cells[(x, y)] = (240, 60, 50)
    cells[(8, 8)] = (120, 240, 120)
    cells[(7, 13)] = (120, 124, 136)  # button
    cells[(8, 13)] = (120, 124, 136)
    return item(cells)


def parcel():
    """A cardboard box tied with string, with a paper label."""
    cells = {}
    card, card_l, card_d = (180, 136, 84), (212, 170, 112), (132, 96, 56)
    for y in range(5, 15):
        for x in range(1, 13):
            cells[(x, y)] = card
    for y in range(2, 5):
        for x in range(1 + (5 - y), 13 + (5 - y)):
            if x < 16:
                cells[(x, y)] = card_l  # top
    for y in range(2, 13):
        for x in range(13, 16):
            if y >= 2 + (x - 13) and y <= 14 - (x - 12):
                cells[(x, y)] = card_d  # side
    string = (236, 230, 214)
    for y in range(5, 15):
        cells[(7, y)] = string
    for x in range(1, 13):
        cells[(x, 9)] = string
    cells[(6, 8)] = string
    cells[(8, 8)] = string
    for y in range(11, 14):
        for x in range(2, 6):
            cells[(x, y)] = (246, 244, 236)
    cells[(3, 12)] = (60, 60, 70)
    cells[(4, 12)] = (60, 60, 70)
    return item(cells)


def main(root):
    item_dir = os.path.join(root, "textures", "item")
    for name, fn in (("dummy", dummy), ("gate_key", gate_key), ("rope_hook", rope_hook), ("tablet", tablet), ("parcel", parcel)):
        fn().save(os.path.join(item_dir, name + ".png"))


if __name__ == "__main__":
    main(sys.argv[1])
