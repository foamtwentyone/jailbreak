"""Cellbreak 0.5.0: block models, blockstates and item models of the prison look.

Usage: python3 gen_decor.py <assets_dir>

Furniture models are built from boxes (Blockbench style) in pixels, facing north. Two-block
furniture is designed in one coordinate space (0..32 along z for long pieces, along y for tall
ones) and split into its base and extra parts here.
"""
import json
import math
import os
import sys

MAT = {
    "frame": "mat_powder_black", "chrome": "mat_chrome", "pad": "mat_leather_black", "padr": "mat_leather_red",
    "rubber": "mat_rubber", "belt": "mat_belt", "console": "mat_console", "plate": "mat_plate", "bag": "mat_bag",
    "cam": "mat_camera_body", "lens": "mat_lens", "led": "mat_led", "feed0": "mat_feed_0", "feed1": "mat_feed_1",
    "feed2": "mat_feed_2", "steel": "mat_stainless", "mirror": "mat_mirror", "hex": "mat_hex", "grip": "mat_grip",
    "toilet": "mat_toilet", "water": "mat_water", "sframe": "mat_screen_frame", "keys": "mat_keyboard",
    "light": "light_panel",
}

FACES = ("north", "south", "east", "west", "up", "down")


def auto_uv(face, f, t):
    x1, y1, z1 = f
    x2, y2, z2 = t
    if face == "north":
        u = (16 - x2, 16 - x1); v = (16 - y2, 16 - y1)
    elif face == "south":
        u = (x1, x2); v = (16 - y2, 16 - y1)
    elif face == "east":
        u = (16 - z2, 16 - z1); v = (16 - y2, 16 - y1)
    elif face == "west":
        u = (z1, z2); v = (16 - y2, 16 - y1)
    elif face == "up":
        u = (x1, x2); v = (z1, z2)
    else:
        u = (x1, x2); v = (16 - z2, 16 - z1)
    fu, fv = fit(u), fit(v)
    return [fu[0], fv[0], fu[1], fv[1]]


def fit(r):
    """Keep a uv span inside 0..16 (shift it, squeeze it if it is longer than the texture)."""
    a, b = r
    w = b - a
    if w > 16:
        return (0.0, 16.0)
    a = a % 16
    if a + w > 16:
        a = 16 - w
    return (round(a, 3), round(a + w, 3))


class Model:
    def __init__(self):
        self.elements = []
        self.textures = set()

    def box(self, f, t, tex, full=(), faces=None, rot=None, uvs=None, skip=()):
        """tex: material key or {face: key}; full: faces that get the whole texture."""
        el = {"from": [round(v, 3) for v in f], "to": [round(v, 3) for v in t], "faces": {}}
        for face in faces or FACES:
            if face in skip:
                continue
            key = tex[face] if isinstance(tex, dict) and face in tex else (tex["*"] if isinstance(tex, dict) else tex)
            self.textures.add(key)
            uv = [0, 0, 16, 16] if face in full else auto_uv(face, f, t)
            if uvs and face in uvs:
                uv = uvs[face]
            el["faces"][face] = {"uv": uv, "texture": "#" + key}
        if rot:
            el["rotation"] = rot
        self.elements.append(el)
        return el

    def json(self, particle):
        tex = {k: "cellbreak:block/" + MAT[k] for k in sorted(self.textures)}
        tex["particle"] = "cellbreak:block/" + MAT[particle]
        return {"textures": tex, "elements": self.elements}


def rot(axis, angle, origin):
    return {"axis": axis, "angle": angle, "origin": list(origin)}


def split(model, axis, particle):
    """Two parts from one model: axis 'z' (long, extra = z 16..32) or 'y' (tall, extra = y 16..32)."""
    i = 2 if axis == "z" else 1
    base, extra = Model(), Model()
    for el in model.elements:
        f, t = el["from"], el["to"]
        r = el.get("rotation")
        if r or (f[i] >= 16) or (t[i] <= 16):
            centre = (f[i] + t[i]) / 2
            target = extra if centre >= 16 else base
            e = json.loads(json.dumps(el))
            if target is extra:
                e["from"][i] -= 16
                e["to"][i] -= 16
                if r:
                    e["rotation"]["origin"][i] -= 16
            target.elements.append(e)
            continue
        lo = json.loads(json.dumps(el))
        hi = json.loads(json.dumps(el))
        lo["to"][i] = 16
        hi["from"][i] = 0
        hi["to"][i] = t[i] - 16
        for part, (a, b) in ((lo, (f, lo["to"])), (hi, (hi["from"], hi["to"]))):
            for face, d in part["faces"].items():
                if d["uv"] != [0, 0, 16, 16]:
                    d["uv"] = auto_uv(face, a, b)
        base.elements.append(lo)
        extra.elements.append(hi)
    for m in (base, extra):
        m.textures = set(model.textures)
    return base.json(particle), extra.json(particle)


# ---------------------------------------------------------------- the models

def plate_pair(m, x, y0, y1, z0, z1, thick=2):
    """A weight plate standing in the y-z plane at x (face along x)."""
    m.box((x, y0, z0), (x + thick, y1, z1), {"*": "rubber", "east": "plate", "west": "plate"}, full=("east", "west"))


def barbell(m, y, z, x0=-12, x1=28, plates=True):
    m.box((x0, y, z), (x1, y + 1, z + 1), "chrome")
    for a, b in ((x0, x0 + 8), (x1 - 8, x1)):
        m.box((a, y - 0.5, z - 0.5), (b, y + 1.5, z + 1.5), "chrome")
    if plates:
        r = 6.5
        for x in (x0 + 4, x0 + 6):
            plate_pair(m, x, y + 0.5 - r, y + 0.5 + r, z + 0.5 - r, z + 0.5 + r)
        for x in (x1 - 6, x1 - 8):
            plate_pair(m, x, y + 0.5 - r, y + 0.5 + r, z + 0.5 - r, z + 0.5 + r)


def bench_press():
    m = Model()
    for z in (3, 13.5):
        m.box((6, 0, z), (10, 5, z + 1.5), "frame")
        m.box((4, 0, z), (12, 1, z + 1.5), "frame")
    m.box((7, 3, 4.5), (9, 4.5, 13.5), "frame")
    m.box((5, 5, 2.5), (11, 7.5, 15.5), "pad", full=("up",))
    for x in (2.5, 12):
        m.box((x, 0, 2), (x + 1.5, 19, 3.5), "frame")
        m.box((x - 1, 0, 0.5), (x + 2.5, 1, 5), "frame")
        m.box((x - 0.25, 15.5, 3.5), (x + 1.75, 16.5, 5), "chrome")
    barbell(m, 16.5, 4)
    return m.json("frame")


def exercise_bike():
    m = Model()
    m.box((3, 0, 2), (13, 1.5, 4), "frame")
    m.box((3, 0, 13), (13, 1.5, 15), "frame")
    m.box((7, 1, 3), (9, 3, 14), "frame")
    m.box((7.25, 2, 1), (8.75, 11, 10), {"*": "frame", "east": "plate", "west": "plate"}, full=("east", "west"))
    m.box((7, 3, 5), (9, 14, 7), "frame")
    m.box((3, 14, 4), (13, 15, 6), "chrome")
    m.box((3, 13.75, 4), (5, 15.25, 6), "rubber")
    m.box((11, 13.75, 4), (13, 15.25, 6), "rubber")
    m.box((6, 15, 5.5), (10, 17.5, 7), {"*": "frame", "north": "console", "south": "console"}, full=("north", "south"))
    m.box((7, 3, 11), (9, 11, 13), "frame")
    m.box((5.5, 11, 9.5), (10.5, 12.5, 14.5), "pad", full=("up",))
    m.box((5, 5, 8), (11, 6, 9), "chrome")
    m.box((3.5, 4, 7.5), (5, 5, 9.5), "rubber")
    m.box((11, 6, 7.5), (12.5, 7, 9.5), "rubber")
    return m.json("frame")


def dumbbell_rack():
    m = Model()
    for x in (0, 14.5):
        m.box((x, 0, 4), (x + 1.5, 13, 5.5), "frame")
        m.box((x, 0, 10.5), (x + 1.5, 13, 12), "frame")
        m.box((x, 0, 3), (x + 1.5, 1, 13), "frame")
    m.box((0, 5, 4), (16, 6, 12), "frame")
    m.box((0, 11, 6), (16, 12, 12), "frame")
    for i, x in enumerate((1.75, 6.25, 10.75)):
        h = 3.5 - i * 0.3
        m.box((x, 6, 4), (x + h, 6 + h, 6.5), "hex")
        m.box((x, 6, 9.5), (x + h, 6 + h, 12), "hex")
        m.box((x + h / 2 - 0.5, 6 + h / 2 - 0.5, 6.5), (x + h / 2 + 0.5, 6 + h / 2 + 0.5, 9.5), "grip")
    for x in (2, 6.5, 11):
        m.box((x, 12, 6.5), (x + 2.5, 14.5, 8), "hex")
        m.box((x, 12, 10), (x + 2.5, 14.5, 11.5), "hex")
        m.box((x + 0.75, 12.75, 8), (x + 1.75, 13.75, 10), "grip")
    return m.json("frame")


def plate_tree():
    m = Model()
    m.box((3, 0, 3), (13, 1, 13), "frame")
    m.box((7, 1, 7), (9, 21, 9), "frame")
    for y, size in ((6, 6.5), (16, 5)):
        m.box((9, y - 0.5, 7.5), (12, y + 0.5, 8.5), "chrome")
        m.box((4, y - 0.5, 7.5), (7, y + 0.5, 8.5), "chrome")
        for x in (9.5, 11):
            plate_pair(m, x, y - size, y + size, 8 - size, 8 + size, 1.5)
        for x in (5, 3.5):
            plate_pair(m, x, y - size, y + size, 8 - size, 8 + size, 1.5)
    return m.json("frame")


def gym_mirror():
    m = Model()
    m.box((0, 0, 15), (16, 16, 16), "chrome")
    m.box((0.5, 0.5, 14.5), (15.5, 15.5, 15), {"*": "chrome", "north": "mirror"}, full=("north",))
    return m.json("mirror")


def cctv_camera():
    m = Model()
    m.box((6, 5, 15), (10, 11, 16), "cam")
    m.box((7.5, 7.5, 11), (8.5, 8.5, 15), "cam")
    m.box((7, 7, 10.5), (9, 9, 12), "frame")
    r = rot("x", 22.5, (8, 9, 11))
    m.box((5, 7, 2), (11, 11, 11), "cam", rot=r)
    m.box((4.5, 11, 1), (11.5, 11.5, 11), "cam", rot=r)
    m.box((5.5, 7.5, 1.5), (10.5, 10.5, 2), {"*": "frame", "north": "lens"}, full=("north",), rot=r)
    m.box((9.5, 10, 1.25), (10.25, 10.5, 1.5), "led", rot=r)
    return m.json("cam")


def security_monitor():
    m = Model()
    m.box((3, 0, 1), (13, 0.6, 4.5), {"*": "frame", "up": "keys"}, full=("up",))
    m.box((13.5, 0, 1.5), (15, 0.6, 3.5), "frame")
    for x0, feed, angle in ((0.5, "feed0", 22.5), (8.5, "feed1", -22.5)):
        cx = x0 + 3.5
        m.box((cx - 1.5, 0, 7.5), (cx + 1.5, 0.5, 10), "frame")
        m.box((cx - 0.5, 0.5, 8.5), (cx + 0.5, 4, 9.5), "frame")
        r = rot("y", angle, (cx, 8, 8))
        m.box((x0, 3.5, 7.5), (x0 + 7, 12, 8.5), {"*": "sframe", "north": feed}, full=("north",), rot=r)
    return m.json("sframe")


def steel_toilet():
    m = Model()
    m.box((3, 0, 10), (13, 15, 16), "toilet")
    m.box((3, 15, 10), (13, 16, 16), "toilet")
    m.box((4.5, 15.2, 11), (11.5, 16.05, 15), "water")
    m.box((7.5, 16, 14.5), (8.5, 18, 15.5), "chrome")
    m.box((7.5, 17, 12.5), (8.5, 18, 15.5), "chrome")
    m.box((6, 11, 9.75), (10, 12, 10), "chrome")
    m.box((4, 0, 3), (12, 5, 10), "toilet")
    m.box((3.5, 5, 2.5), (12.5, 6, 10), "toilet")
    m.box((5, 5.4, 3.5), (11, 6.05, 9), "water")
    return m.json("toilet")


def steel_sink():
    m = Model()
    m.box((3, 10, 7), (13, 14, 16), "toilet")
    m.box((4, 13.5, 8), (12, 14.05, 15), "water")
    m.box((7, 7, 14), (9, 10, 16), "toilet")
    m.box((7.5, 14, 14.5), (8.5, 16.5, 15.5), "chrome")
    m.box((7.5, 15.5, 12), (8.5, 16.5, 15.5), "chrome")
    m.box((4, 18, 15.5), (12, 28, 16), {"*": "steel", "north": "mirror"}, full=("north",))
    return m.json("toilet")


def treadmill():
    m = Model()
    m.box((2, 0, 3), (14, 3.5, 30), "frame")
    m.box((3, 3.5, 4), (13, 4, 29), "belt")
    for x in (2, 13):
        m.box((x, 3.5, 6), (x + 1, 4.5, 28), "rubber")
        m.box((x, 4, 4), (x + 1.5, 22, 6), "frame")
        m.box((x, 16, 6), (x + 1.5, 17.5, 13), "rubber")
    m.box((2, 0, 2), (14, 5.5, 7), "frame")
    m.box((2, 21, 3), (14, 25, 8), {"*": "frame", "up": "console", "south": "console"}, full=("up", "south"))
    m.box((2, 0, 29), (14, 4.5, 31), "frame")
    return split(m, "z", "frame")


def power_rack():
    m = Model()
    m.box((0, 0, 0), (16, 1, 16), "rubber")
    for x in (1, 13):
        for z in (2, 12):
            m.box((x, 1, z), (x + 2, 32, z + 2), "frame")
        m.box((x, 30, 2), (x + 2, 32, 14), "frame")
        m.box((x - 0.5, 10, 1), (x + 2.5, 11, 15), "chrome")
        m.box((x, 19, 0.5), (x + 2, 20, 2), "chrome")
    for z in (2, 12):
        m.box((1, 30, z), (15, 32, z + 2), "frame")
    m.box((-1, 28.5, 0.5), (17, 29.5, 1.5), "grip")
    barbell(m, 20, 0.75)
    return split(m, "y", "frame")


def punching_bag():
    m = Model()
    uv_side = lambda y1, y2: [0, round(16 - y2 / 2, 3), 16, round(16 - y1 / 2, 3)]
    for f, t, r in (((3, 0, 5), (13, 27, 11), None), ((5, 0, 3), (11, 27, 13), None),
                    ((4.5, 0, 4.5), (11.5, 27, 11.5), rot("y", 45, (8, 8, 8)))):
        m.box(f, t, "bag", rot=r, uvs={k: uv_side(0, 27) for k in ("north", "south", "east", "west")})
    m.box((4.5, 27, 4.5), (11.5, 28.5, 11.5), "pad")
    for x, z in ((5, 5), (10, 5), (5, 10), (10, 10)):
        m.box((x, 28.5, z), (x + 1, 31, z + 1), "chrome")
    m.box((6, 30.5, 6), (10, 31.5, 10), "chrome")
    m.box((7.5, 31.5, 7.5), (8.5, 32, 8.5), "chrome")
    base, extra = split(m, "y", "bag")
    # The split re-computes uvs of the bag's sides; give them the whole-bag mapping back.
    for part, off in ((base, 0), (extra, 16)):
        for el in part["elements"]:
            for face, d in el["faces"].items():
                if d["texture"] == "#bag" and face not in ("up", "down"):
                    d["uv"] = uv_side(el["from"][1] + off, el["to"][1] + off)
    return base, extra


def caged_lamp(hanging):
    m = Model()
    y = 4 if hanging else 0
    m.box((5, y, 5), (11, y + 1.5, 11), "frame")
    m.box((6, y + 1.5, 6), (10, y + 7.5, 10), "light", full=("north", "south", "east", "west"))
    for x, z in ((5.5, 5.5), (10, 5.5), (5.5, 10), (10, 10)):
        m.box((x, y + 1.5, z), (x + 0.5, y + 8, z + 0.5), "frame")
    m.box((5.5, y + 4.5, 5.5), (10.5, y + 5, 10.5), "frame", faces=("up", "down"))
    m.box((5, y + 8, 5), (11, y + 9, 11), "frame")
    if hanging:
        m.box((7.5, 13, 7.5), (8.5, 16, 8.5), "frame")
    return m.json("frame")


# ---------------------------------------------------------------- blockstates and simple models

def cube(tex, side=None, bottom=None):
    if side is None:
        return {"parent": "minecraft:block/cube_all", "textures": {"all": tex}}
    return {"parent": "minecraft:block/cube_bottom_top", "textures": {"top": tex, "side": side, "bottom": bottom or tex}}


def facing_variants(model, extra=None):
    v = {}
    for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        if extra:
            for part in ("base", "extra"):
                d = {"model": "cellbreak:block/%s_%s" % (model, part)}
                if y:
                    d["y"] = y
                v["facing=%s,part=%s" % (f, part)] = d
        else:
            d = {"model": "cellbreak:block/" + model}
            if y:
                d["y"] = y
            v["facing=" + f] = d
    return {"variants": v}


def stairs_states(name):
    m = "cellbreak:block/" + name
    v = {}
    rots = {"east": 0, "south": 90, "west": 180, "north": 270}
    for facing, fy in rots.items():
        for half in ("bottom", "top"):
            for shape in ("straight", "inner_left", "inner_right", "outer_left", "outer_right"):
                model = m + ("_inner" if "inner" in shape else "_outer" if "outer" in shape else "")
                y = fy
                if shape.endswith("left"):
                    y = (y - 90) % 360 if half == "bottom" else y
                if shape.endswith("right") and half == "top":
                    y = (y + 90) % 360
                d = {"model": model}
                if half == "top":
                    d["x"] = 180
                    d["uvlock"] = True
                if y:
                    d["y"] = y
                    d["uvlock"] = True
                v["facing=%s,half=%s,shape=%s" % (facing, half, shape)] = d
    return {"variants": v}


def slab_states(name, full):
    m = "cellbreak:block/" + name
    return {"variants": {"type=bottom": {"model": m}, "type=top": {"model": m + "_top"}, "type=double": {"model": full}}}


def wall_states(name):
    m = "cellbreak:block/" + name
    parts = [{"when": {"up": "true"}, "apply": {"model": m + "_post"}}]
    for d, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for h, suffix in (("low", "_side"), ("tall", "_side_tall")):
            parts.append({"when": {d: h}, "apply": {"model": m + suffix, "y": y, "uvlock": True}})
    return {"multipart": parts}


def pane_states(name):
    m = "cellbreak:block/" + name
    return {"multipart": [
        {"apply": {"model": m + "_post"}},
        {"when": {"north": "true"}, "apply": {"model": m + "_side"}},
        {"when": {"east": "true"}, "apply": {"model": m + "_side", "y": 90}},
        {"when": {"south": "true"}, "apply": {"model": m + "_side_alt"}},
        {"when": {"west": "true"}, "apply": {"model": m + "_side_alt", "y": 90}},
        {"when": {"north": "false"}, "apply": {"model": m + "_noside"}},
        {"when": {"east": "false"}, "apply": {"model": m + "_noside_alt"}},
        {"when": {"south": "false"}, "apply": {"model": m + "_noside_alt", "y": 90}},
        {"when": {"west": "false"}, "apply": {"model": m + "_noside", "y": 270}}]}


def door_states(name):
    m = "cellbreak:block/" + name
    v = {}
    base_y = {"east": 0, "south": 90, "west": 180, "north": 270}
    for facing, fy in base_y.items():
        for half in ("lower", "upper"):
            for hinge in ("left", "right"):
                for open_ in ("false", "true"):
                    part = "bottom" if half == "lower" else "top"
                    model = "%s_%s_%s%s" % (m, part, hinge, "_open" if open_ == "true" else "")
                    y = fy
                    if open_ == "true":
                        y = (fy + (90 if hinge == "left" else 270)) % 360
                    d = {"model": model}
                    if y:
                        d["y"] = y
                    v["facing=%s,half=%s,hinge=%s,open=%s" % (facing, half, hinge, open_)] = d
    return {"variants": v}


def trapdoor_states(name):
    m = "cellbreak:block/" + name
    v = {}
    ys = {"north": 0, "east": 90, "south": 180, "west": 270}
    for facing, y in ys.items():
        for half in ("bottom", "top"):
            for open_ in ("false", "true"):
                if open_ == "true":
                    d = {"model": m + "_open"}
                    yy = y
                    if half == "top":
                        d["x"] = 180
                        yy = (y + 180) % 360
                    if yy:
                        d["y"] = yy
                else:
                    d = {"model": m + ("_top" if half == "top" else "_bottom")}
                    if y:
                        d["y"] = y
                v["facing=%s,half=%s,open=%s" % (facing, half, open_)] = d
    return {"variants": v}


def main(root):
    bs_dir = os.path.join(root, "blockstates")
    bm_dir = os.path.join(root, "models", "block")
    im_dir = os.path.join(root, "models", "item")

    def write(d, name, obj):
        with open(os.path.join(d, name + ".json"), "w") as fh:
            json.dump(obj, fh, indent=1)

    def simple(name, model, item_model=None):
        write(bm_dir, name, model)
        write(bs_dir, name, {"variants": {"": {"model": "cellbreak:block/" + name}}})
        write(im_dir, name, item_model or {"parent": "cellbreak:block/" + name})

    b = "cellbreak:block/"
    # Walls and floors (blocks whose textures were redrawn keep their old model files).
    for name in ("prison_wall_band", "cracked_prison_wall_band", "concrete_wall", "cracked_concrete_wall",
                 "asphalt", "dark_concrete", "steel_panel", "smooth_concrete"):
        simple(name, cube(b + name))
    simple("concrete_column", cube(b + "smooth_concrete", b + "concrete_column"))
    # Floors are ceilings seen from below: acoustic tiles underneath.
    for name in ("prison_floor", "floor_tile", "gym_floor", "roof_gravel"):
        model = cube(b + name, b + name, b + "ceiling_tile")
        simple(name, model)
        write(bm_dir, "fake_" + name, model)
    simple("light_panel", cube(b + "light_panel"))
    simple("wired_glass", cube(b + "wired_glass"))
    for fake, tex in (("fake_prison_wall_band", "prison_wall_band"), ("fake_concrete_wall", "concrete_wall"),
                      ("fake_concrete_column", "concrete_column")):
        write(bm_dir, fake, cube(b + tex))
    for fake in ("fake_prison_wall_band", "fake_concrete_wall", "fake_concrete_column", "fake_floor_tile", "fake_gym_floor",
                 "fake_roof_gravel"):
        write(bs_dir, fake, {"variants": {"": {"model": "cellbreak:block/" + fake}}})

    # Slabs, stairs, walls.
    for name, top, side, full in (("concrete_slab", "smooth_concrete", "smooth_concrete", "smooth_concrete"),
                                  ("floor_slab", "prison_floor", "smooth_concrete", "prison_floor"),
                                  ("dark_concrete_slab", "dark_concrete", "dark_concrete", "dark_concrete")):
        t = {"bottom": b + top, "top": b + top, "side": b + side}
        write(bm_dir, name, {"parent": "minecraft:block/slab", "textures": t})
        write(bm_dir, name + "_top", {"parent": "minecraft:block/slab_top", "textures": t})
        write(bs_dir, name, slab_states(name, "cellbreak:block/" + full))
        write(im_dir, name, {"parent": "cellbreak:block/" + name})
    for name, tex in (("concrete_stairs", "smooth_concrete"), ("dark_concrete_stairs", "dark_concrete"),
                      ("steel_seat", "steel_table")):
        t = {"bottom": b + tex, "top": b + tex, "side": b + tex}
        write(bm_dir, name, {"parent": "minecraft:block/stairs", "textures": t})
        write(bm_dir, name + "_inner", {"parent": "minecraft:block/inner_stairs", "textures": t})
        write(bm_dir, name + "_outer", {"parent": "minecraft:block/outer_stairs", "textures": t})
        write(bs_dir, name, stairs_states(name))
        write(im_dir, name, {"parent": "cellbreak:block/" + name})
    wall = {"wall": b + "smooth_concrete"}
    write(bm_dir, "concrete_barrier_post", {"parent": "minecraft:block/template_wall_post", "textures": wall})
    write(bm_dir, "concrete_barrier_side", {"parent": "minecraft:block/template_wall_side", "textures": wall})
    write(bm_dir, "concrete_barrier_side_tall", {"parent": "minecraft:block/template_wall_side_tall", "textures": wall})
    write(bm_dir, "concrete_barrier_inventory", {"parent": "minecraft:block/wall_inventory", "textures": wall})
    write(bs_dir, "concrete_barrier", wall_states("concrete_barrier"))
    write(im_dir, "concrete_barrier", {"parent": "cellbreak:block/concrete_barrier_inventory"})

    # Panes: wired glass and the chain-link fence (built like the cell bars).
    pane = {"pane": b + "wired_glass", "edge": b + "wired_glass_pane_top"}
    for part, parent in (("post", "template_glass_pane_post"), ("side", "template_glass_pane_side"),
                         ("side_alt", "template_glass_pane_side_alt"), ("noside", "template_glass_pane_noside"),
                         ("noside_alt", "template_glass_pane_noside_alt")):
        write(bm_dir, "wired_glass_pane_" + part, {"parent": "minecraft:block/" + parent, "textures": pane})
    write(bs_dir, "wired_glass_pane", pane_states("wired_glass_pane"))
    write(im_dir, "wired_glass_pane", {"parent": "minecraft:item/generated", "textures": {"layer0": b + "wired_glass"}})
    link = {"particle": b + "chain_link", "bars": b + "chain_link", "edge": b + "chain_link_edge"}
    for part in ("post_ends", "post", "cap", "cap_alt", "side", "side_alt"):
        write(bm_dir, "chain_link_" + part, {"parent": "minecraft:block/iron_bars_" + part, "textures": link})
    with open(os.path.join(bs_dir, "cell_bars.json")) as fh:
        bars = fh.read().replace("cell_bars", "chain_link")
    with open(os.path.join(bs_dir, "chain_link.json"), "w") as fh:
        fh.write(bars)
    write(im_dir, "chain_link", {"parent": "minecraft:item/generated", "textures": {"layer0": b + "chain_link"}})

    # The cell door.
    door = {"top": b + "cell_door_top", "bottom": b + "cell_door_bottom"}
    for part in ("bottom", "top"):
        for hinge in ("left", "right"):
            for op in ("", "_open"):
                write(bm_dir, "cell_door_%s_%s%s" % (part, hinge, op),
                      {"parent": "minecraft:block/door_%s_%s%s" % (part, hinge, op), "textures": door})
    write(bs_dir, "cell_door", door_states("cell_door"))
    write(im_dir, "cell_door", {"parent": "minecraft:item/generated", "textures": {"layer0": "cellbreak:item/cell_door"}})

    # Steel table (trapdoor).
    t = {"texture": b + "steel_table"}
    for part in ("bottom", "top", "open"):
        write(bm_dir, "steel_table_" + part, {"parent": "minecraft:block/template_orientable_trapdoor_" + part, "textures": t})
    write(bs_dir, "steel_table", trapdoor_states("steel_table"))
    write(im_dir, "steel_table", {"parent": "cellbreak:block/steel_table_bottom"})

    # Razor wire and the lamp.
    write(bm_dir, "razor_wire", {"parent": "minecraft:block/cross", "textures": {"cross": b + "razor_wire"}})
    write(bs_dir, "razor_wire", {"variants": {"": {"model": "cellbreak:block/razor_wire"}}})
    write(im_dir, "razor_wire", {"parent": "minecraft:item/generated", "textures": {"layer0": b + "razor_wire"}})
    write(bm_dir, "caged_lamp", caged_lamp(False))
    write(bm_dir, "caged_lamp_hanging", caged_lamp(True))
    write(bs_dir, "caged_lamp", {"variants": {"hanging=false": {"model": "cellbreak:block/caged_lamp"},
                                              "hanging=true": {"model": "cellbreak:block/caged_lamp_hanging"}}})
    write(im_dir, "caged_lamp", {"parent": "cellbreak:block/caged_lamp"})

    # Furniture.
    for name, fn in (("cctv_camera", cctv_camera), ("security_monitor", security_monitor), ("steel_toilet", steel_toilet),
                     ("steel_sink", steel_sink), ("bench_press", bench_press), ("exercise_bike", exercise_bike),
                     ("dumbbell_rack", dumbbell_rack), ("plate_tree", plate_tree), ("gym_mirror", gym_mirror)):
        write(bm_dir, name, fn())
        write(bs_dir, name, facing_variants(name))
        write(im_dir, name, {"parent": "cellbreak:block/" + name})
    for name, fn in (("treadmill", treadmill), ("power_rack", power_rack), ("punching_bag", punching_bag)):
        base, extra = fn()
        write(bm_dir, name + "_base", base)
        write(bm_dir, name + "_extra", extra)
        write(bs_dir, name, facing_variants(name, extra=True))
        write(im_dir, name, {"parent": "cellbreak:block/" + name + "_base"})


if __name__ == "__main__":
    main(sys.argv[1])
