"""Cellbreak textures, 0.5.0: the realistic prison look.

Usage: python3 gen_textures_v8.py <assets_dir>

Building materials are 64x64 and tile seamlessly (noise is made periodic with an FFT filter):
painted cinder block walls, precast concrete, polished concrete and tiled floors, rubber gym
flooring, brushed steel, wired glass, chain-link, razor wire and light fittings. Model materials
(gym equipment, cameras, monitors, toilets) are 32x32.
"""
import math
import os
import random
import sys

import numpy as np
from PIL import Image

N = 64


# ---------------------------------------------------------------- noise

def noise(size, beta, seed, shape=None):
    """Periodic 1/f^beta noise in [0, 1]; tiles seamlessly."""
    rng = np.random.default_rng(seed)
    h, w = shape or (size, size)
    white = rng.standard_normal((h, w))
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    f = np.sqrt(fx * fx + fy * fy)
    f[0, 0] = 1.0
    spec = np.fft.fft2(white) / f ** beta
    spec[0, 0] = 0
    out = np.real(np.fft.ifft2(spec))
    out -= out.min()
    out /= max(out.max(), 1e-9)
    return out


def speckle(size, density, seed):
    rng = np.random.default_rng(seed)
    return (rng.random((size, size)) < density).astype(float)


def to_img(rgb, alpha=None):
    rgb = np.clip(rgb, 0, 255).astype(np.uint8)
    if alpha is None:
        alpha = np.full(rgb.shape[:2], 255, np.uint8)
    else:
        alpha = np.clip(alpha, 0, 255).astype(np.uint8)
    return Image.fromarray(np.dstack([rgb, alpha]), "RGBA")


def colour(base, shade):
    """base rgb * shade (2D) -> HxWx3."""
    return np.dstack([base[i] * shade for i in range(3)])


def shift(a, dx, dy):
    return np.roll(np.roll(a, dy, axis=0), dx, axis=1)


# ---------------------------------------------------------------- walls

def cmu_layout(size=N, course=16, unit=32, joint=2):
    """Cinder block running bond: per-pixel block id, joint mask and edge shading."""
    ys, xs = np.mgrid[0:size, 0:size]
    row = ys // course
    off = (row % 2) * (unit // 2)
    col = ((xs + off) // unit)
    bid = row * 10 + col % (size // unit)
    ly = ys % course
    lx = (xs + off) % unit
    joint_mask = (ly >= course - joint) | (lx >= unit - joint)
    # Bevel: light along the top of each block, shadow under it and right of it.
    edge = np.zeros((size, size))
    edge[(ly == 0)] += 0.08
    edge[(lx == 0)] += 0.04
    edge[(ly == course - joint - 1)] -= 0.10
    edge[(lx == unit - joint - 1)] -= 0.06
    return bid, joint_mask, edge


def painted_cmu(paint, seed, band=False, grime=0.5):
    bid, joint, edge = cmu_layout()
    rng = random.Random(seed)
    tone = np.zeros((N, N))
    for b in np.unique(bid):
        tone[bid == b] = rng.uniform(-0.035, 0.035)
    pores = speckle(N, 0.045, seed + 1)
    pores = pores * (0.12 + 0.1 * noise(N, 1.0, seed + 2))
    surface = noise(N, 1.6, seed + 3) * 0.06 - 0.03
    stain = noise(N, 2.2, seed + 4)
    shade = 1.0 + tone + surface + edge - pores
    # Grime collects low on the wall (the band takes the scuffs).
    if band:
        scuff = (noise(N, 1.2, seed + 5) > 0.82).astype(float) * 0.10
        shade -= scuff
    shade -= np.clip(stain - 0.6, 0, 1) * grime * 0.25
    joint_shade = 0.78 + 0.05 * noise(N, 1.0, seed + 6)
    shade = np.where(joint, joint_shade, shade)
    return colour(paint, shade)


def add_cracks(rgb, seed, inner=(120, 118, 112), count=2, length=56):
    """Dark cracks with chipped paint around them (bare concrete shows through)."""
    rng = random.Random(seed)
    img = rgb.copy()
    chips = np.zeros((N, N))
    for c in range(count):
        x, y = rng.uniform(8, 56), rng.uniform(4, 20) if c == 0 else rng.uniform(4, 60)
        ang = rng.uniform(math.pi * 0.3, math.pi * 0.7)
        for _ in range(length):
            ang += rng.uniform(-0.55, 0.55)
            x += math.cos(ang)
            y += math.sin(ang) * 0.9 + 0.3
            xi, yi = int(x) % N, int(y) % N
            img[yi, xi] = (38, 36, 34)
            if rng.random() < 0.25:
                img[yi, (xi + 1) % N] = (84, 82, 78)
            if rng.random() < 0.05:
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        if dx * dx + dy * dy <= 4 + rng.random() * 2:
                            chips[(yi + dy) % N, (xi + dx) % N] = 1
            if y > N + 4:
                break
    grain = noise(N, 1.0, seed + 7) * 0.2 + 0.9
    bare = colour(inner, grain)
    mask = (chips > 0) & ~np.all(img == (38, 36, 34), axis=2)
    img[mask] = bare[mask]
    return img


def precast_concrete(seed, base=(158, 156, 150)):
    """Exterior wall: board-formed precast concrete, tie holes, rain streaks."""
    ys, xs = np.mgrid[0:N, 0:N]
    shade = 1.0 + (noise(N, 1.8, seed) - 0.5) * 0.14
    shade += (noise(N, 0.6, seed + 1) - 0.5) * 0.05
    shade -= speckle(N, 0.05, seed + 2) * 0.12
    shade += speckle(N, 0.02, seed + 3) * 0.08
    # Rain streaks: vertical, stretched noise.
    streak = noise(N, 1.4, seed + 4, (N, N))
    streak = np.repeat(streak.mean(axis=0, keepdims=True), N, axis=0)
    shade -= np.clip(streak - 0.55, 0, 1) * 0.35 * (0.6 + 0.4 * ys / N)
    # Formwork board lines every 16 px, faint.
    shade -= ((ys % 16) == 0) * 0.04
    # Panel joint along the top and left edge (sealant).
    shade = np.where(ys < 1, 0.62, shade)
    shade = np.where(xs < 1, 0.66, shade)
    rgb = colour(base, shade)
    # Form-tie holes.
    for cx, cy in ((16, 24), (48, 24), (16, 56), (48, 56)):
        r = np.hypot(xs - cx, ys - cy)
        rgb[r < 1.6] = (70, 68, 64)
        ring = (r >= 1.6) & (r < 2.4)
        rgb[ring] = rgb[ring] * 0.88
    return rgb


def column(seed):
    ys, xs = np.mgrid[0:N, 0:N]
    shade = 1.0 + (noise(N, 2.0, seed) - 0.5) * 0.08 - speckle(N, 0.03, seed + 1) * 0.08
    shade -= (xs < 3) * 0.12 + (xs > N - 4) * 0.16
    shade += (xs == 3) * 0.06
    rgb = colour((196, 192, 182), shade)
    return rgb


def smooth_concrete(seed):
    shade = 1.0 + (noise(N, 2.0, seed) - 0.5) * 0.1 - speckle(N, 0.03, seed + 1) * 0.08 + speckle(N, 0.015, seed + 2) * 0.06
    return colour((176, 174, 168), shade)


# ---------------------------------------------------------------- floors

def polished_concrete(seed, base=(150, 150, 146), dark=False):
    ys, xs = np.mgrid[0:N, 0:N]
    clouds = noise(N, 2.0, seed)
    shade = 1.0 + (clouds - 0.5) * 0.16 + (noise(N, 1.0, seed + 1) - 0.5) * 0.05
    agg = speckle(N, 0.06, seed + 2)
    shade -= agg * (0.05 + 0.1 * noise(N, 0.5, seed + 3))
    shade += speckle(N, 0.025, seed + 4) * 0.08
    # Saw-cut joint along two edges (1 m slabs).
    shade = np.where(ys == 0, shade * 0.72, shade)
    shade = np.where(xs == 0, shade * 0.72, shade)
    shade = np.where(ys == 1, shade * 1.05, shade)
    if dark:
        base = (96, 98, 100)
    return colour(base, shade)


def ceramic_tile(seed, base=(214, 214, 208), grout=(140, 138, 132), tiles=4):
    ys, xs = np.mgrid[0:N, 0:N]
    size = N // tiles
    rng = random.Random(seed)
    tone = np.zeros((N, N))
    for ty in range(tiles):
        for tx in range(tiles):
            tone[ty * size:(ty + 1) * size, tx * size:(tx + 1) * size] = rng.uniform(-0.03, 0.03)
    shade = 1.0 + tone + (noise(N, 1.5, seed + 1) - 0.5) * 0.05
    ly, lx = ys % size, xs % size
    shade += ((ly == 1) | (lx == 1)) * 0.04
    shade -= ((ly == size - 2) | (lx == size - 2)) * 0.05
    stains = np.clip(noise(N, 2.4, seed + 2) - 0.72, 0, 1) * 0.6
    shade -= stains
    rgb = colour(base, shade)
    g = (ly == 0) | (lx == 0)
    rgb[g] = colour(grout, 0.95 + 0.1 * noise(N, 1.0, seed + 3))[g]
    return rgb


def rubber_floor(seed):
    ys, xs = np.mgrid[0:N, 0:N]
    shade = 1.0 + (noise(N, 1.0, seed) - 0.5) * 0.25
    rgb = colour((40, 41, 44), shade)
    rng = np.random.default_rng(seed + 1)
    flecks = rng.random((N, N))
    rgb[flecks < 0.05] = (92, 96, 104)
    rgb[(flecks >= 0.05) & (flecks < 0.075)] = (58, 92, 140)
    rgb[(flecks >= 0.075) & (flecks < 0.085)] = (150, 150, 150)
    seam = (ys == 0) | (xs == 0)
    rgb[seam] = (22, 22, 24)
    return rgb


def asphalt(seed):
    shade = 1.0 + (noise(N, 1.2, seed) - 0.5) * 0.2 + (noise(N, 2.2, seed + 1) - 0.5) * 0.12
    rgb = colour((70, 70, 72), shade)
    rng = np.random.default_rng(seed + 2)
    r = rng.random((N, N))
    rgb[r < 0.06] = rgb[r < 0.06] * 1.45
    rgb[(r > 0.06) & (r < 0.09)] = rgb[(r > 0.06) & (r < 0.09)] * 0.6
    return rgb


def roof_gravel(seed):
    rng = np.random.default_rng(seed)
    base = 1.0 + (noise(N, 0.8, seed + 1) - 0.5) * 0.5
    rgb = colour((128, 126, 120), base)
    # Pebbles: small blobs of varied tone with a shadow below.
    for _ in range(420):
        x, y = rng.integers(0, N, 2)
        t = rng.uniform(0.7, 1.25)
        c = np.array([150, 146, 138]) * t
        rgb[y, x] = c
        rgb[y, (x + 1) % N] = c * 0.95
        rgb[(y + 1) % N, x] = c * 0.6
    return rgb


# ---------------------------------------------------------------- metal and glass

def brushed_steel(seed, base=(176, 180, 186), vertical=True, panel=True):
    ys, xs = np.mgrid[0:N, 0:N]
    rng = np.random.default_rng(seed)
    lines = rng.standard_normal(N)
    lines = np.convolve(lines, np.ones(3) / 3, mode="same")
    brush = lines[None, :] if vertical else lines[:, None]
    shade = 1.0 + brush * 0.035 + (noise(N, 2.0, seed + 1) - 0.5) * 0.12
    shade += (ys / N) * -0.06
    if panel:
        shade = np.where((xs < 2) | (ys < 2), shade * 1.12, shade)
        shade = np.where((xs > N - 3) | (ys > N - 3), shade * 0.78, shade)
    rgb = colour(base, shade)
    if panel:
        for cx, cy in ((6, 6), (N - 7, 6), (6, N - 7), (N - 7, N - 7)):
            r = np.hypot(xs - cx, ys - cy)
            rgb[r < 2.2] = (120, 122, 128)
            rgb[(r < 1.2)] = (210, 212, 216)
            rgb[(r < 2.2) & (ys > cy) & (xs > cx)] = (90, 92, 96)
    return rgb


def wired_glass(size=32):
    """Translucent glass with a diamond wire mesh and a frame."""
    ys, xs = np.mgrid[0:size, 0:size]
    rgb = np.zeros((size, size, 3))
    rgb[:] = (196, 214, 220)
    alpha = np.full((size, size), 60.0)
    grad = (xs + ys) / (2 * size)
    rgb = rgb * (0.95 + 0.1 * grad[..., None])
    wire = (((xs + ys) % 8) == 0) | (((xs - ys) % 8) == 0)
    rgb[wire] = (150, 156, 160)
    alpha[wire] = 210
    frame = (xs == 0) | (ys == 0) | (xs == size - 1) | (ys == size - 1)
    rgb[frame] = (110, 114, 120)
    alpha[frame] = 255
    # A pale reflection streak.
    streak = np.abs((xs - ys) - 8) < 2
    rgb[streak & ~wire & ~frame] = (232, 240, 244)
    alpha[streak & ~wire & ~frame] = 90
    return to_img(rgb, alpha)


def chain_link(size=32):
    ys, xs = np.mgrid[0:size, 0:size]
    rgb = np.zeros((size, size, 3))
    alpha = np.zeros((size, size))
    a = (xs + ys) % 8
    b = (xs - ys) % 8
    wire = (a == 0) | (b == 0)
    hi = (a == 1) & ~wire
    rgb[wire] = (168, 172, 176)
    alpha[wire] = 255
    rgb[hi] = (110, 114, 118)
    alpha[hi] = 255
    # Twisted knuckles where wires cross.
    cross = (a == 0) & (b == 0)
    rgb[cross] = (210, 214, 218)
    # Top rail.
    rail = ys < 2
    rgb[rail] = (150, 154, 158)
    rgb[ys == 0] = (196, 200, 204)
    alpha[rail] = 255
    return to_img(rgb, alpha)


def chain_link_edge(size=32):
    ys, xs = np.mgrid[0:size, 0:size]
    rgb = np.zeros((size, size, 3))
    alpha = np.zeros((size, size))
    post = (xs >= 13) & (xs <= 18)
    shade = 1.0 + (xs - 15.5) * -0.05
    rgb[post] = (np.array([160, 164, 168])[None, :] * shade[post][:, None])
    alpha[post] = 255
    return to_img(rgb, alpha)


def razor_wire(size=32):
    """Concertina coils seen from the side: overlapping loops with barbs."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    rng = random.Random(77)
    for k in range(4):
        cx = 4 + k * 8
        for t in range(0, 360, 4):
            a = math.radians(t)
            x = cx + 9 * math.cos(a) * 0.55
            y = 16 + 11 * math.sin(a)
            xi, yi = int(round(x)) % size, int(round(y))
            if 0 <= yi < size:
                c = (196, 200, 206, 255) if math.sin(a) < 0 else (128, 132, 138, 255)
                px[xi, yi] = c
                if t % 40 == 0:
                    for d in (-1, 1):
                        bx, by = (xi + d) % size, yi + d
                        if 0 <= by < size:
                            px[bx, by] = (220, 224, 228, 255)
    return img


def light_panel(size=32):
    ys, xs = np.mgrid[0:size, 0:size]
    rgb = np.zeros((size, size, 3))
    rgb[:] = (250, 250, 246)
    grid = ((xs % 4) == 0) | ((ys % 4) == 0)
    rgb[grid] = (232, 234, 232)
    frame = (xs < 2) | (ys < 2) | (xs > size - 3) | (ys > size - 3)
    rgb[frame] = (196, 198, 200)
    rgb[(xs == 1) | (ys == 1)] = (220, 222, 224)
    return to_img(rgb)


def ceiling_tile(seed):
    """Acoustic ceiling tile (sides of the light panel)."""
    shade = 1.0 - speckle(N, 0.12, seed) * 0.08 + (noise(N, 1.0, seed + 1) - 0.5) * 0.04
    return colour((222, 220, 212), shade)


# ---------------------------------------------------------------- the cell door

def cell_door(top, size=32):
    ys, xs = np.mgrid[0:size, 0:size]
    rng = np.random.default_rng(5 if top else 6)
    shade = 1.0 + (noise(size, 1.6, 11 if top else 12) - 0.5) * 0.1 - rng.random((size, size)) * 0.03
    paint = (92, 106, 116)
    rgb = colour(paint, shade)
    # Frame of the leaf: lighter top-left edge, shadow bottom-right.
    rgb[:, :2] = rgb[:, :2] * 0.75
    rgb[:, -2:] = rgb[:, -2:] * 0.7
    if top:
        rgb[:2, :] = rgb[:2, :] * 0.75
        # Vision panel: dark wired glass in a steel frame.
        win = (xs >= 9) & (xs <= 22) & (ys >= 5) & (ys <= 22)
        rgb[win] = (34, 42, 48)
        wire = win & ((((xs + ys) % 4) == 0) | (((xs - ys) % 4) == 0))
        rgb[wire] = (70, 80, 86)
        frame = ((xs == 8) | (xs == 23)) & (ys >= 4) & (ys <= 23) | ((ys == 4) | (ys == 23)) & (xs >= 8) & (xs <= 23)
        rgb[frame] = (150, 160, 166)
        hl = win & (np.abs((xs - ys) - 6) < 2)
        rgb[hl] = (80, 96, 104)
        # Name card holder.
        card = (xs >= 11) & (xs <= 20) & (ys >= 26) & (ys <= 29)
        rgb[card] = (220, 216, 200)
        rgb[(ys == 27) & (xs >= 12) & (xs <= 18)] = (60, 60, 64)
    else:
        rgb[-2:, :] = rgb[-2:, :] * 0.7
        # Food hatch.
        hatch = (xs >= 8) & (xs <= 23) & (ys >= 4) & (ys <= 11)
        rgb[hatch] = rgb[hatch] * 0.82
        rgb[(ys == 4) & (xs >= 8) & (xs <= 23)] = (150, 160, 166)
        rgb[(ys == 11) & (xs >= 8) & (xs <= 23)] = (52, 60, 66)
        rgb[(ys >= 6) & (ys <= 7) & (xs >= 14) & (xs <= 17)] = (40, 44, 48)
        # Kick plate.
        kick = ys >= 24
        rgb[kick] = colour((150, 154, 158), 1.0 + (noise(size, 1.0, 13) - 0.5) * 0.1)[kick]
        rgb[ys == 24] = (190, 194, 198)
    # Hinges on the left, lock box on the right.
    for hy in ((6, 10) if top else (16, 20)):
        pass
    hinge_rows = [(6, 10)] if top else [(18, 22)]
    for a, b in hinge_rows:
        rgb[a:b, 0:3] = (60, 64, 68)
    if not top:
        rgb[0:6, 25:30] = (70, 74, 78)
        rgb[2:4, 27:29] = (24, 24, 26)
    # Bolts.
    for bx in (4, 27):
        for by in ((3, 28) if top else (3, 20)):
            rgb[by, bx] = (150, 160, 166)
            rgb[by + 1, bx + 1] = (50, 56, 60)
    return to_img(rgb)


def cell_door_item():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    top = cell_door(True).resize((8, 8), Image.LANCZOS)
    bottom = cell_door(False).resize((8, 8), Image.LANCZOS)
    img.paste(top, (4, 0))
    img.paste(bottom, (4, 8))
    return img


# ---------------------------------------------------------------- model materials (32x32)

M = 32


def mat(base, seed, rough=0.08, speck=0.0, beta=1.4):
    shade = 1.0 + (noise(M, beta, seed) - 0.5) * rough * 2
    if speck:
        shade -= speckle(M, speck, seed + 1) * 0.1
    return colour(base, shade)


def powder_black(seed=21):
    return to_img(mat((44, 46, 50), seed, 0.06, 0.08))


def chrome(seed=22):
    ys, xs = np.mgrid[0:M, 0:M]
    t = (np.sin(ys / M * math.pi * 2 + 0.6) + 1) / 2
    shade = 0.62 + 0.55 * t + (noise(M, 1.5, seed) - 0.5) * 0.08
    return to_img(colour((196, 200, 206), shade))


def leather(base, seed):
    ys, xs = np.mgrid[0:M, 0:M]
    shade = 1.0 + (noise(M, 0.9, seed) - 0.5) * 0.12 + (noise(M, 2.0, seed + 1) - 0.5) * 0.1
    rgb = colour(base, shade)
    stitch = ((xs == 2) | (xs == M - 3) | (ys == 2) | (ys == M - 3)) & (((xs + ys) % 3) != 0)
    rgb[stitch] = np.array(base) * 1.9 + 20
    rgb[(xs < 1) | (ys < 1)] = np.array(base) * 0.6
    return to_img(rgb)


def rubber(seed=24):
    return to_img(mat((30, 30, 32), seed, 0.05, 0.05))


def belt():
    ys, xs = np.mgrid[0:M, 0:M]
    shade = 1.0 + ((ys % 4) == 0) * -0.25 + (noise(M, 1.0, 25) - 0.5) * 0.1
    rgb = colour((36, 38, 40), shade)
    return to_img(rgb)


def console():
    ys, xs = np.mgrid[0:M, 0:M]
    rgb = colour((40, 42, 46), 1.0 + (noise(M, 1.2, 26) - 0.5) * 0.06)
    lcd = (xs >= 6) & (xs <= 25) & (ys >= 5) & (ys <= 14)
    rgb[lcd] = (40, 70, 60)
    rgb[lcd & (ys % 3 == 1) & (xs % 2 == 0) & (xs < 20)] = (120, 230, 170)
    for i, c in enumerate([(220, 60, 50), (60, 180, 80), (230, 200, 60), (80, 140, 220)]):
        bx = 6 + i * 5
        rgb[19:23, bx:bx + 3] = c
    rgb[26:29, 8:24] = (70, 72, 78)
    return to_img(rgb)


def plate(text_colour=(230, 230, 230)):
    """A weight plate seen face-on: round (cutout), raised rim, hub, kg marking."""
    ys, xs = np.mgrid[0:M, 0:M]
    r = np.hypot(xs - 15.5, ys - 15.5)
    rgb = colour((34, 34, 36), 1.0 + (noise(M, 1.2, 27) - 0.5) * 0.1)
    alpha = np.where(r <= 15.6, 255, 0)
    rgb[(r > 13) & (r <= 15.6)] = (52, 52, 56)
    rgb[(r > 4) & (r <= 6)] = (60, 60, 64)
    rgb[r <= 4] = (176, 180, 186)
    rgb[r <= 2] = (40, 40, 42)
    # "20" in small block digits at the top.
    for x, y in [(12, 8), (13, 8), (14, 8), (14, 9), (13, 10), (12, 11), (12, 12), (13, 12), (14, 12),
                 (17, 8), (18, 8), (19, 8), (17, 9), (19, 9), (17, 10), (19, 10), (17, 11), (19, 11), (17, 12), (18, 12), (19, 12)]:
        rgb[y, x] = text_colour
    rgb[22:24, 13:19] = (200, 50, 40)
    return to_img(rgb, alpha)


def bag():
    ys, xs = np.mgrid[0:M, 0:M]
    shade = 1.0 + (noise(M, 1.0, 28) - 0.5) * 0.14
    shade += np.sin(xs / M * math.pi) * 0.12
    rgb = colour((150, 28, 30), shade)
    band = ((ys >= 4) & (ys <= 6)) | ((ys >= 25) & (ys <= 27))
    rgb[band] = colour((30, 30, 32), shade)[band]
    logo = (ys >= 12) & (ys <= 19) & (xs >= 10) & (xs <= 21)
    rgb[logo] = (230, 226, 220)
    rgb[logo & (((xs - 10) % 4 == 0) | (ys == 16))] = (150, 28, 30)
    return to_img(rgb)


def camera_body():
    ys, xs = np.mgrid[0:M, 0:M]
    rgb = colour((226, 228, 230), 1.0 + (noise(M, 1.5, 29) - 0.5) * 0.06 - ys / M * 0.08)
    return to_img(rgb)


def lens():
    ys, xs = np.mgrid[0:M, 0:M]
    r = np.hypot(xs - 15.5, ys - 15.5)
    rgb = colour((30, 32, 36), np.ones((M, M)))
    rgb[r < 11] = (12, 14, 18)
    rgb[(r < 11) & (r > 9)] = (70, 72, 80)
    rgb[r < 6] = (20, 30, 60)
    rgb[(r < 3)] = (40, 60, 120)
    rgb[(np.hypot(xs - 12, ys - 12) < 2)] = (200, 210, 230)
    # IR LEDs around the lens.
    for a in range(0, 360, 45):
        x = int(15.5 + 13.5 * math.cos(math.radians(a)))
        y = int(15.5 + 13.5 * math.sin(math.radians(a)))
        rgb[y, x] = (110, 30, 30)
    return to_img(rgb)


def led():
    rgb = np.zeros((M, M, 3))
    rgb[:] = (255, 40, 30)
    return to_img(rgb)


def cctv_feed(seed, scene):
    """A grainy grey CCTV picture with a timestamp: corridor, yard or cell block."""
    ys, xs = np.mgrid[0:M, 0:M]
    g = np.full((M, M), 70.0)
    if scene == 0:  # corridor in perspective
        g += np.where(np.abs(xs - 16) < (ys - 6) * 0.9, 40, 0)
        g -= np.where((np.abs(xs - 16) < 3) & (ys > 6) & (ys < 14), 30, 0)
        g += np.where((ys % 6 == 0) & (np.abs(xs - 16) < (ys - 6) * 0.9), 15, 0)
    elif scene == 1:  # yard with fence
        g += np.where(ys > 18, 30, 0)
        g -= np.where(((xs + ys) % 5 == 0) & (ys < 18), 20, 0)
        g += np.where((ys > 20) & (np.abs(xs - 10) < 2), -40, 0)
    else:  # cell doors
        for k in range(4):
            g -= np.where((np.abs(xs - (4 + k * 8)) < 2) & (ys > 8) & (ys < 24), 35, 0)
        g += np.where(ys > 24, 25, 0)
    g += np.random.default_rng(seed).normal(0, 9, (M, M))
    g = g * (0.85 + 0.15 * ((ys % 2) == 0))
    rgb = np.dstack([g * 0.9, g, g * 0.92])
    rgb[1:3, 1:14] = (210, 210, 210)
    rgb[1:3, 2:14:3] = (40, 40, 40)
    rgb[1:3, 28:30] = (220, 40, 40)
    return to_img(rgb)


def stainless(seed=31):
    rng = np.random.default_rng(seed)
    lines = np.convolve(rng.standard_normal(M), np.ones(2) / 2, mode="same")
    shade = 1.0 + lines[None, :] * 0.04 + (noise(M, 2.0, seed) - 0.5) * 0.14
    return to_img(colour((188, 192, 196), shade))


def mirror():
    ys, xs = np.mgrid[0:M, 0:M]
    t = (xs + (M - ys)) / (2 * M)
    rgb = np.dstack([170 + 40 * t, 190 + 40 * t, 200 + 40 * t])
    streak = np.abs((xs - ys) + 6) < 3
    rgb[streak] = (236, 244, 248)
    streak2 = np.abs((xs - ys) - 10) < 1
    rgb[streak2] = (226, 236, 240)
    return to_img(rgb)


def hex_head():
    ys, xs = np.mgrid[0:M, 0:M]
    rgb = colour((36, 36, 38), 1.0 + (noise(M, 1.0, 33) - 0.5) * 0.1)
    rgb[(xs + ys) % 16 == 0] = (60, 60, 64)
    return to_img(rgb)


def grip():
    ys, xs = np.mgrid[0:M, 0:M]
    knurl = ((xs + ys) % 3 == 0) | ((xs - ys) % 3 == 0)
    rgb = colour((150, 154, 160), 1.0 - knurl * 0.25)
    return to_img(rgb)


def toilet_steel():
    ys, xs = np.mgrid[0:M, 0:M]
    shade = 1.0 + (noise(M, 2.0, 34) - 0.5) * 0.2 + np.sin(ys / 5) * 0.03
    return to_img(colour((180, 184, 188), shade))


def water():
    ys, xs = np.mgrid[0:M, 0:M]
    shade = 1.0 + (noise(M, 1.5, 35) - 0.5) * 0.2
    return to_img(colour((120, 150, 170), shade))


def screen_frame():
    return to_img(mat((22, 22, 24), 36, 0.04))


def keyboard():
    ys, xs = np.mgrid[0:M, 0:M]
    rgb = colour((30, 30, 32), np.ones((M, M)))
    keys = ((xs % 3) != 0) & ((ys % 4) != 0)
    rgb[keys] = (70, 72, 76)
    return to_img(rgb)


# ---------------------------------------------------------------- main

def main(root):
    block_dir = os.path.join(root, "textures", "block")
    item_dir = os.path.join(root, "textures", "item")
    os.makedirs(block_dir, exist_ok=True)

    paint = (204, 200, 190)
    band_paint = (86, 102, 112)
    blocks = {
        "prison_wall": painted_cmu(paint, 100),
        "prison_wall_band": painted_cmu(band_paint, 110, band=True),
        "concrete_wall": precast_concrete(120),
        "concrete_column": column(130),
        "smooth_concrete": smooth_concrete(131),
        "prison_floor": polished_concrete(140),
        "floor_tile": ceramic_tile(150),
        "gym_floor": rubber_floor(160),
        "asphalt": asphalt(170),
        "roof_gravel": roof_gravel(180),
        "dark_concrete": polished_concrete(190, dark=True),
        "steel_panel": brushed_steel(200),
        "steel_table": brushed_steel(210, panel=False, vertical=False),
        "ceiling_tile": ceiling_tile(220),
    }
    blocks["cracked_prison_wall"] = add_cracks(blocks["prison_wall"], 101)
    blocks["cracked_prison_wall_band"] = add_cracks(blocks["prison_wall_band"], 111)
    blocks["cracked_concrete_wall"] = add_cracks(blocks["concrete_wall"], 121, inner=(110, 108, 102))
    for name, rgb in blocks.items():
        to_img(rgb).save(os.path.join(block_dir, name + ".png"))

    wired_glass().save(os.path.join(block_dir, "wired_glass.png"))
    wired_glass().crop((0, 0, 32, 32)).save(os.path.join(block_dir, "wired_glass_pane_top.png"))
    chain_link().save(os.path.join(block_dir, "chain_link.png"))
    chain_link_edge().save(os.path.join(block_dir, "chain_link_edge.png"))
    razor_wire().save(os.path.join(block_dir, "razor_wire.png"))
    light_panel().save(os.path.join(block_dir, "light_panel.png"))
    cell_door(True).save(os.path.join(block_dir, "cell_door_top.png"))
    cell_door(False).save(os.path.join(block_dir, "cell_door_bottom.png"))
    cell_door_item().save(os.path.join(item_dir, "cell_door.png"))

    mats = {
        "mat_powder_black": powder_black(), "mat_chrome": chrome(), "mat_leather_black": leather((34, 32, 32), 41),
        "mat_leather_red": leather((128, 26, 28), 42), "mat_rubber": rubber(), "mat_belt": belt(),
        "mat_console": console(), "mat_plate": plate(), "mat_bag": bag(), "mat_camera_body": camera_body(),
        "mat_lens": lens(), "mat_led": led(), "mat_feed_0": cctv_feed(51, 0), "mat_feed_1": cctv_feed(52, 1),
        "mat_feed_2": cctv_feed(53, 2), "mat_stainless": stainless(), "mat_mirror": mirror(), "mat_hex": hex_head(),
        "mat_grip": grip(), "mat_toilet": toilet_steel(), "mat_water": water(), "mat_screen_frame": screen_frame(),
        "mat_keyboard": keyboard(),
    }
    for name, img in mats.items():
        img.save(os.path.join(block_dir, name + ".png"))


if __name__ == "__main__":
    main(sys.argv[1])
