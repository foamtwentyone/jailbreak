"""Render a preview sheet of Cellbreak textures.

Usage: python3 preview.py <assets_dir> <out.png>
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# Model-space boxes (x0, y0, z0, w, h, d) for a biped; front faces -z.
BOXES = {
    "right_leg": (0, 0, 0, 4, 12, 4),
    "left_leg": (4, 0, 0, 4, 12, 4),
    "body": (0, 12, 0, 8, 12, 4),
    "right_arm": (-4, 12, 0, 4, 12, 4),
    "left_arm": (8, 12, 0, 4, 12, 4),
    "head": (0, 24, -2, 8, 8, 8),
}
UV = {
    "head": (0, 0), "body": (16, 16), "right_arm": (40, 16),
    "left_arm": (32, 48), "right_leg": (0, 16), "left_leg": (16, 48),
}


def box_texels(name, skin):
    """Yield (quad corners in 3D, rgba, normal) for every texel of a skin box."""
    x0, y0, z0, w, h, d = BOXES[name]
    u, v = UV[name]
    x1, y1, z1 = x0 + w, y0 + h, z0 + d
    faces = {
        # face: (tex origin, tex w, tex h, fn(i, j) -> 4 corners, normal)
        "front": ((u + d, v + d), w, h,
                  lambda i, j: [(x0 + i, y1 - j, z0), (x0 + i + 1, y1 - j, z0),
                                (x0 + i + 1, y1 - j - 1, z0), (x0 + i, y1 - j - 1, z0)], (0, 0, -1)),
        "back": ((u + 2 * d + w, v + d), w, h,
                 lambda i, j: [(x1 - i, y1 - j, z1), (x1 - i - 1, y1 - j, z1),
                               (x1 - i - 1, y1 - j - 1, z1), (x1 - i, y1 - j - 1, z1)], (0, 0, 1)),
        "right": ((u, v + d), d, h,
                  lambda i, j: [(x0, y1 - j, z1 - i), (x0, y1 - j, z1 - i - 1),
                                (x0, y1 - j - 1, z1 - i - 1), (x0, y1 - j - 1, z1 - i)], (-1, 0, 0)),
        "left": ((u + d + w, v + d), d, h,
                 lambda i, j: [(x1, y1 - j, z0 + i), (x1, y1 - j, z0 + i + 1),
                               (x1, y1 - j - 1, z0 + i + 1), (x1, y1 - j - 1, z0 + i)], (1, 0, 0)),
        "top": ((u + d, v), w, d,
                lambda i, j: [(x0 + i, y1, z1 - j), (x0 + i + 1, y1, z1 - j),
                              (x0 + i + 1, y1, z1 - j - 1), (x0 + i, y1, z1 - j - 1)], (0, 1, 0)),
    }
    for (tu, tv), tw, th, quad, n in faces.values():
        for j in range(th):
            for i in range(tw):
                c = skin.getpixel((tu + i, tv + j))
                if c[3] > 0:
                    yield quad(i, j), c, n


def render_biped(skin, cam, scale=9):
    f = np.array(cam, float)
    f /= np.linalg.norm(f)
    up = np.array([0, 1, 0.0])
    right = np.cross(up, f)
    right /= np.linalg.norm(right)
    sup = np.cross(f, right)
    light = np.array([0.4, 0.9, -0.6])
    light /= np.linalg.norm(light)
    polys = []
    for name in BOXES:
        for quad, c, n in box_texels(name, skin):
            n = np.array(n, float)
            if np.dot(n, f) >= 0:
                continue
            pts = np.array(quad, float)
            depth = np.dot(pts.mean(axis=0), f)
            k = 0.62 + 0.38 * max(0.0, float(np.dot(n, light)))
            col = tuple(int(min(255, ch * k)) for ch in c[:3])
            scr = [(float(np.dot(p, right)), -float(np.dot(p, sup))) for p in pts]
            polys.append((depth, scr, col))
    polys.sort(key=lambda t: -t[0])
    xs = [p[0] for _, s, _ in polys for p in s]
    ys = [p[1] for _, s, _ in polys for p in s]
    mx, my = min(xs), min(ys)
    W = int((max(xs) - mx) * scale) + 4
    H = int((max(ys) - my) * scale) + 4
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dr = ImageDraw.Draw(img)
    for _, scr, col in polys:
        dr.polygon([((x - mx) * scale + 2, (y - my) * scale + 2) for x, y in scr], fill=col)
    return img


def render_cube(tex, size=96):
    """Isometric cube with the same texture on all visible faces."""
    t = tex.convert("RGBA").resize((size, size), Image.NEAREST)
    s = size
    W, H = int(s * 1.732) + 2, int(s * 2) + 2
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    hw = s * 0.866

    def warp(img, coeffs, k):
        f = img.transform((W, H), Image.AFFINE, coeffs, Image.NEAREST)
        px = np.array(f).astype(float)
        px[..., :3] *= k
        return Image.fromarray(px.clip(0, 255).astype("uint8"))

    # Inverse affine maps (output -> texture coords).
    # Top face: tex(u,v) -> out(hw + (u - v)*hw/s, (u + v)*0.5)
    a, b = hw / s, -hw / s
    c, d = 0.5, 0.5
    det = a * d - b * c
    ia, ib, ic, id_ = d / det, -b / det, -c / det, a / det
    top = (ia, ib, -(ia * hw), ic, id_, -(ic * hw))
    out.alpha_composite(warp(t, top, 1.0))
    # Left face: tex(u,v) -> out(u*hw/s, s*0.5 + u*0.5 + v)
    a, b, c, d = hw / s, 0, 0.5, 1
    det = a * d - b * c
    ia, ib, ic, id_ = d / det, -b / det, -c / det, a / det
    left = (ia, ib, -(ia * 0 + ib * s * 0.5), ic, id_, -(ic * 0 + id_ * s * 0.5))
    out.alpha_composite(warp(t, left, 0.78))
    # Right face: tex(u,v) -> out(hw + u*hw/s, s + u*(-0.5) + v)
    a, b, c, d = hw / s, 0, -0.5, 1
    det = a * d - b * c
    ia, ib, ic, id_ = d / det, -b / det, -c / det, a / det
    ox, oy = hw, s
    right = (ia, ib, -(ia * ox + ib * oy), ic, id_, -(ic * ox + id_ * oy))
    out.alpha_composite(warp(t, right, 0.6))
    return out


def main():
    root, out_path = sys.argv[1], sys.argv[2]
    tdir = os.path.join(root, "textures")
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 22)
    small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 16)

    sheet = Image.new("RGBA", (1280, 900), (38, 42, 48, 255))
    dr = ImageDraw.Draw(sheet)

    x = 30
    for name, label in (("guard", "Охранник"), ("prisoner", "Заключённый")):
        skin = Image.open(os.path.join(tdir, "entity", name + ".png")).convert("RGBA")
        front = render_biped(skin, (-0.55, -0.35, 1))
        back = render_biped(skin, (0.55, -0.35, -1))
        dr.text((x, 20), label, font=font, fill=(240, 240, 240))
        sheet.alpha_composite(front, (x, 60))
        sheet.alpha_composite(back, (x + front.width + 20, 60))
        x += front.width + back.width + 70

    dr.text((30, 420), "Блоки", font=font, fill=(240, 240, 240))
    blocks = [("prison_wall", "стена"), ("cracked_prison_wall", "стена с трещиной"),
              ("reinforced_wall", "усиленная стена"), ("cell_bars", "решётка"),
              ("vent_cover", "вентиляция"), ("prison_floor", "пол")]
    bx = 30
    for name, label in blocks:
        tex = Image.open(os.path.join(tdir, "block", name + ".png"))
        if name == "cell_bars":
            cube = tex.resize((96, 96), Image.NEAREST)
            sheet.alpha_composite(cube, (bx + 36, 480))
        else:
            cube = render_cube(tex, 96)
            sheet.alpha_composite(cube, (bx, 455))
        dr.text((bx, 660), label, font=small, fill=(210, 210, 210))
        bx += 205

    dr.text((30, 705), "Предметы", font=font, fill=(240, 240, 240))
    items = [("shiv", "заточка"), ("file", "напильник"), ("makeshift_pickaxe", "кирка"),
             ("makeshift_shovel", "лопата"), ("cell_key", "ключ-слепок"),
             ("handcuffs", "наручники"), ("baton", "дубинка")]
    ix = 30
    for name, label in items:
        tex = Image.open(os.path.join(tdir, "item", name + ".png")).convert("RGBA")
        bg = Image.new("RGBA", (112, 112), (58, 62, 70, 255))
        bg.alpha_composite(tex.resize((96, 96), Image.NEAREST), (8, 8))
        sheet.alpha_composite(bg, (ix, 745))
        dr.text((ix, 862), label, font=small, fill=(210, 210, 210))
        ix += 175

    sheet.convert("RGB").save(out_path)


if __name__ == "__main__":
    main()
