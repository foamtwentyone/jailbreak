"""Render a top-down map of the prison from run/prison_map.txt (written by /cbtest map).

Usage: python3 render_map.py <prison_map.txt> <out.png> [slice]
With "slice" the map is cut at head height, so rooms with roofs show their insides.
"""
import sys

from PIL import Image, ImageDraw, ImageFont

COLORS = {
    "prison_wall": (150, 150, 146),
    "cracked_prison_wall": (120, 110, 100),
    "reinforced_wall": (80, 84, 92),
    "cell_bars": (110, 116, 128),
    "prison_floor": (205, 203, 195),
    "grass_block": (96, 150, 64),
    "dirt": (134, 96, 67),
    "yellow_concrete": (240, 190, 30),
    "iron_door": (190, 190, 200),
    "orange_bed": (230, 118, 30),
    "chest": (160, 110, 50),
    "spruce_slab": (110, 80, 50),
    "smooth_stone": (170, 170, 170),
}
LABELS = [
    ((18, 4.5), "Камеры"),
    ((11, 21), "Столовая"),
    ((31.5, 30), "Двор / перекличка"),
    ((40, 3.5), "Карцер"),
    ((43, 11.5), "Охрана"),
]
SCALE = 14


def main():
    src, out = sys.argv[1], sys.argv[2]
    top = 2 if len(sys.argv) > 3 and sys.argv[3] == "slice" else 4
    lines = open(src).read().strip().split("\n")
    rows = [line.split(";") for line in lines[1:]]
    h, w = len(rows), len(rows[0])
    img = Image.new("RGB", (w * SCALE, h * SCALE), (30, 30, 30))
    dr = ImageDraw.Draw(img)
    for z, row in enumerate(rows):
        for x, cell in enumerate(row):
            ids = cell.split(",")  # y = -1, 0, 1, 2, 3
            color = None
            for y in range(top, -1, -1):
                b = ids[y]
                if b in ("air", "light"):
                    continue
                color = COLORS.get(b, (255, 0, 255))
                if y == 3 and b == "prison_wall":
                    color = tuple(int(c * 0.8) for c in color)  # roofs a bit darker
                break
            if color is None:
                color = (30, 30, 30)
            dr.rectangle([x * SCALE, z * SCALE, (x + 1) * SCALE - 1, (z + 1) * SCALE - 1], fill=color)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 16)
    for (x, z), text in LABELS:
        px, pz = (x + 6) * SCALE, (z + 6) * SCALE
        tw = dr.textlength(text, font=font)
        dr.text((px - tw / 2, pz - 9), text, font=font, fill=(255, 255, 255), stroke_width=3, stroke_fill=(0, 0, 0))
    img.save(out)


if __name__ == "__main__":
    main()
