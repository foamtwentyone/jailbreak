"""Render scan_top.txt (written by /cbtest scan) as a top-down picture and summarize scan_blocks.txt.

Usage: python3 render_scan.py <scan_top.txt> <scan_blocks.txt> <out.png> [scale]
"""
import hashlib
import sys
from collections import Counter

from PIL import Image, ImageDraw, ImageFont

KNOWN = {
    "grass_block": (96, 150, 64), "water": (50, 90, 200), "sand": (220, 210, 160), "stone": (125, 125, 125),
    "dirt": (134, 96, 67), "oak_leaves": (60, 120, 40), "birch_leaves": (90, 140, 60), "spruce_leaves": (50, 90, 50),
    "stone_bricks": (140, 140, 140), "iron_bars": (110, 116, 128), "gravel": (150, 140, 135),
    "smooth_stone": (170, 170, 170), "quartz_block": (235, 230, 225), "white_concrete": (225, 228, 230),
    "gray_concrete": (70, 72, 78), "light_gray_concrete": (130, 130, 125), "black_concrete": (15, 15, 20),
    "iron_block": (220, 220, 220), "void": (0, 0, 0), "snow": (250, 250, 250), "short_grass": (96, 150, 64),
}


def color(block):
    if block in KNOWN:
        return KNOWN[block]
    h = hashlib.md5(block.encode()).digest()
    return (60 + h[0] % 180, 60 + h[1] % 180, 60 + h[2] % 180)


def main():
    top, blocks, out = sys.argv[1], sys.argv[2], sys.argv[3]
    scale = int(sys.argv[4]) if len(sys.argv) > 4 else 2
    lines = open(top).read().strip().split("\n")
    min_x, min_z = map(int, lines[0].split())
    rows = [r.split(";") for r in lines[1:]]
    h, w = len(rows), len(rows[0])
    img = Image.new("RGB", (w * scale, h * scale))
    px = img.load()
    for z, row in enumerate(rows):
        for x, cell in enumerate(row):
            block, y = cell.rsplit(":", 1)
            c = color(block)
            k = 0.75 + 0.25 * min(1.0, max(0.0, (int(y) - 50) / 40))
            c = tuple(int(v * k) for v in c)
            for dx in range(scale):
                for dz in range(scale):
                    px[x * scale + dx, z * scale + dz] = c
    dr = ImageDraw.Draw(img)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 12)
    step = 32
    for gx in range((min_x // step + 1) * step, min_x + w, step):
        dr.line([((gx - min_x) * scale, 0), ((gx - min_x) * scale, h * scale)], fill=(255, 255, 255, 60))
        dr.text(((gx - min_x) * scale + 2, 2), str(gx), font=font, fill=(255, 255, 0))
    for gz in range((min_z // step + 1) * step, min_z + h, step):
        dr.line([(0, (gz - min_z) * scale), (w * scale, (gz - min_z) * scale)], fill=(255, 255, 255, 60))
        dr.text((2, (gz - min_z) * scale + 2), str(gz), font=font, fill=(255, 255, 0))
    img.save(out)

    counts = Counter()
    ys = Counter()
    for line in open(blocks):
        x, y, z, b = line.split()
        counts[b] += 1
        ys[int(y)] += 1
    print("built blocks:", sum(counts.values()))
    for b, n in counts.most_common(70):
        print(f"  {b}: {n}")
    print("y levels with most built blocks:", sorted(ys.most_common(15)))


if __name__ == "__main__":
    main()
