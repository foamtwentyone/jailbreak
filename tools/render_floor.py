"""Render one floor of a scanned map: walls at feet/head height, floors below, signs as labels.

Usage: python3 render_floor.py <scan_blocks.txt> <scan_cmds.txt> <y> <x1> <z1> <x2> <z2> <out.png>
"""
import sys

from PIL import Image, ImageDraw, ImageFont

blocks_path, cmds_path = sys.argv[1], sys.argv[2]
y, x1, z1, x2, z2 = map(int, sys.argv[3:8])
out = sys.argv[8]
S = 8
grid = {}
for line in open(blocks_path):
    x, by, z, b = line.split()
    x, by, z = int(x), int(by), int(z)
    if x1 <= x <= x2 and z1 <= z <= z2 and y - 1 <= by <= y + 1:
        grid[(x, by, z)] = b
img = Image.new("RGB", ((x2 - x1 + 1) * S, (z2 - z1 + 1) * S), (40, 70, 40))
dr = ImageDraw.Draw(img)
SOFT = ("slab", "carpet", "pressure_plate", "torch", "lantern", "sign", "button", "rail", "redstone_wire", "trapdoor")
for x in range(x1, x2 + 1):
    for z in range(z1, z2 + 1):
        feet, head, floor = grid.get((x, y, z)), grid.get((x, y + 1, z)), grid.get((x, y - 1, z))
        c = None
        if feet and "door" in feet and "trap" not in feet:
            c = (230, 60, 60)
        elif feet and "bed" in feet:
            c = (240, 150, 40)
        elif feet and ("chest" in feet or "barrel" in feet):
            c = (150, 100, 40)
        elif feet and "bars" in feet:
            c = (90, 140, 220)
        elif (feet and not any(s in feet for s in SOFT)) or (head and not any(s in head for s in SOFT) and "bars" not in head):
            c = (60, 60, 60)
        elif floor:
            c = (205, 200, 190)
        if c:
            dr.rectangle([(x - x1) * S, (z - z1) * S, (x - x1 + 1) * S - 1, (z - z1 + 1) * S - 1], fill=c)
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 10)
for line in open(cmds_path):
    if "sign" not in line.split(" :: ")[0]:
        continue
    head, text = line.split(" :: ", 1)
    sx, sy, sz, _ = head.split()
    sx, sy, sz = int(sx), int(sy), int(sz)
    if x1 <= sx <= x2 and z1 <= sz <= z2 and y - 1 <= sy <= y + 3:
        parts = [p.strip() for p in text.split("|") if p.strip() and "===" not in p]
        label = " ".join(parts)[:28]
        dr.text(((sx - x1) * S, (sz - z1) * S), label, font=font, fill=(255, 255, 0), stroke_width=2, stroke_fill=(0, 0, 0))
for gx in range((x1 // 16 + 1) * 16, x2, 16):
    dr.text(((gx - x1) * S + 1, 1), str(gx), font=font, fill=(255, 255, 255))
for gz in range((z1 // 16 + 1) * 16, z2, 16):
    dr.text((1, (gz - z1) * S + 1), str(gz), font=font, fill=(255, 255, 255))
img.save(out)
