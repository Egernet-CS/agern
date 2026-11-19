"""Animated factory sequence for the Agern concept video (Blender 5.2, bpy).

Builds the hero scene by running art/scene_hero.py (with its still render switched off), removes
the static acorns and animates one issue through the line:

  issue (faint outline) -> drafting station: the spec takes shape (spec-writer)
  -> robot arm fits the cap: molten acorn (builder)
  -> splits into the four review gates in parallel (security, ux, ui-design, code), waits for all
  -> security says CHANGES: the acorn rolls back, the arm works on it again (fix round 1)
  -> four PASS -> merges back, turns the corner, the approval press stamps the seal (approver)
  -> rolls out through the shipping door (merge to main).

Render:  Blender -b -P art/video/scene_anim.py -- --out <dir> [--preview] [--step N]
                                                   [--frames A-B] [--still F1,F2,...]
Writes RGBA PNGs with a transparent background (<dir>/0001.png ...). Existing frames are kept,
so an interrupted render resumes. compose.py does the colour/bloom pass and the captions.
Timings are in seconds at 24 fps; TIMELINE (exported to <dir>/timeline.json) is what compose.py
uses to place the captions.
"""
import json
import math
import os
import sys

import bpy
from mathutils import Euler, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.dirname(HERE)
sys.path.insert(0, ART)

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []


def arg(name, default=None):
    return ARGS[ARGS.index(name) + 1] if name in ARGS else default


PREVIEW = "--preview" in ARGS
OUT = arg("--out", os.path.join(ART, "build", "video_3d"))
STEP = int(arg("--step", "1"))
FPS = 24
DURATION = 35.0

# ------------------------------------------------------------------ build the hero scene
# scene_hero.py ends with a still render; run its source without that call (patching bpy.ops
# does not stick, operators are looked up afresh on every access).
_hero = os.path.join(ART, "scene_hero.py")
_src = open(_hero).read()
_call = "bpy.ops.render.render(write_still=True)"
assert _src.count(_call) == 1, "scene_hero.py changed: update scene_anim.py"
_argv = sys.argv
sys.argv = [sys.argv[0]]
exec(compile(_src.replace(_call, "pass"), _hero, "exec"), {"__name__": "__main__", "__file__": _hero})
sys.argv = _argv

import factory_kit as K  # noqa: E402  (same module instance the hero scene used)

scene = bpy.context.scene
P = K.palette()
Z = K.BELT_Z
OBJ = bpy.data.objects


def base(o):
    return o.name.split(".")[0]


def wloc(o):
    return o.matrix_world.translation


# remove the static products, the held cap and the scene camera
ACORN_PARTS = {"nut", "cap", "stem", "seal", "carrier", "carrier_cup", "spec_nut", "spec_cap",
               "spec_core", "held_cap", "cam"}
doomed = [o for o in OBJ if base(o) in ACORN_PARTS]
doomed += [o for o in OBJ if base(o) == "cyl" and (wloc(o) - Vector((1.2, 0, 1.92))).length < 0.3]
for o in doomed:
    OBJ.remove(o, do_unlink=True)

# ------------------------------------------------------------------ animation helpers


def F(t):
    return 1 + round(t * FPS)


def set_interp(idblock, kind="LINEAR"):
    """Force an interpolation on every key of an ID's action (used for constant swaps)."""
    ad = idblock.animation_data
    if not ad or not ad.action:
        return
    for fc in _fcurves(ad.action):
        for kp in fc.keyframe_points:
            kp.interpolation = kind


def _fcurves(action):
    if hasattr(action, "fcurves"):
        return list(action.fcurves)
    out = []   # layered actions (Blender 4.4+)
    for layer in action.layers:
        for strip in layer.strips:
            for bag in strip.channelbags:
                out.extend(bag.fcurves)
    return out


def loc(o, t, xyz):
    o.location = xyz
    o.keyframe_insert("location", frame=F(t))


def rot(o, t, xyz):
    o.rotation_euler = xyz
    o.keyframe_insert("rotation_euler", frame=F(t))


def scl(o, t, s):
    o.scale = (s, s, s)
    o.keyframe_insert("scale", frame=F(t))


def pop(o, t, show, dur=0.2):
    """Scale an object in (show) or out over `dur` seconds starting at t."""
    a, b = (0.0, 1.0) if show else (1.0, 0.0)
    scl(o, t, a)
    scl(o, t + dur, b)


def val(sock_or_obj, attr, t, v):
    setattr(sock_or_obj, attr, v)
    sock_or_obj.keyframe_insert(attr, frame=F(t))


def rig(name):
    return K.empty((0, 0, 0), name=name)


def capture(fn):
    """Run fn() and return the objects it created (top-level only)."""
    before = set(OBJ)
    fn()
    return [o for o in set(OBJ) - before if o.parent is None]


def make_acorn(name, **kw):
    e = rig(name)
    for o in capture(lambda: K.acorn((0, 0, 0), P, **kw)):
        o.parent = e
    return e


# ------------------------------------------------------------------ extra materials
GREEN = K.srgb("#5be3a4")
RED = K.srgb("#8fb4ff")      # the UI's CHANGES colour
EMBER_HOT = K.srgb("#ff8a3d")
P["issue"] = K.emissive("issue_wire", K.CYAN, 0.9)


def anim_emissive(name, color, strength):
    m = K.emissive(name, color, strength)
    return m, m.node_tree.nodes["Emission"]


# ------------------------------------------------------------------ the products
LANES = (-2.1, -0.7, 0.7, 2.1)
ROLES = ("security", "ux", "ui-design", "code")       # front (camera side) to back
CX = 13.9


def issue_rig():
    e = rig("issue")
    nut = K.lathe([(r * 1.2, h * 1.2) for r, h in K.NUT_PROFILE], (0, 0, 0.05), P["issue"],
                  segments=8, name="issue_nut")
    nut.modifiers.new("wire", "WIREFRAME").thickness = 0.014
    K.cyl(0.27 * 1.2, 0.07 * 1.2, (0, 0, 0.042), P["graphite"], verts=16, bevel=0.01,
          name="issue_carrier")
    for o in OBJ:
        if o.parent is None and base(o) in ("issue_nut", "issue_carrier"):
            o.parent = e
    return e


ISSUE = issue_rig()
SPEC = make_acorn("spec", kind="wire", size=1.2)
SOLID = make_acorn("solid", kind="solid", size=1.2)
SEAL = K.torus(0.255 * 1.2, 0.022 * 1.2, (0, 0, 0.05 + 0.47 * 1.2), P["amber"], name="seal_anim")
SEAL.parent = SOLID
LANE = [make_acorn(f"lane_{r}", kind="solid", size=0.95) for r in ROLES]

# warm "needs work" glow that travels with the acorn on the way back
REWORK = K.point((0, -0.25, 0.7), EMBER_HOT, 0.0, radius=0.2, name="rework_light")
REWORK.parent = SOLID

# hologram above the drafting pad
HOLO = make_acorn("holo", kind="wire", size=1.5, carrier=False)
HOLO.location = (-3.6, 0.0, Z + 0.35)

for o in (ISSUE, SPEC, SOLID, *LANE):
    o.location = (-14, 0, Z)
for o in (SPEC, SOLID, *LANE):
    o.scale = (0, 0, 0)

# ------------------------------------------------------------------ robot arm (IK on the static rig)
ARM_S = 1.15
PED_H = 0.45
AX = 1.2
yaw = OBJ["arm_yaw"]
sh, el, wr = OBJ["arm_shoulder"], OBJ["arm_elbow"], OBJ["arm_wrist"]
L1, L2, L3 = 1.75 * ARM_S, 1.45 * ARM_S, 0.62 * ARM_S
SH0, EL0, WR0 = sh.rotation_euler.y, el.rotation_euler.y, wr.rotation_euler.y


def wrist_of(a_sh, a_el):
    r = L1 * math.sin(a_sh) + L2 * math.sin(a_sh + a_el)
    z = L1 * math.cos(a_sh) + L2 * math.cos(a_sh + a_el)
    return r, z


R0, Z0 = wrist_of(SH0, EL0)


def ik(dz, dr=0.0):
    """Joint angles that move the wrist by (dr forward, dz up) with the gripper pointing down."""
    r, z = R0 + dr, Z0 + dz
    d2 = r * r + z * z
    c = max(-1.0, min(1.0, (d2 - L1 * L1 - L2 * L2) / (2 * L1 * L2)))
    a_el = math.acos(c)
    a_sh = math.atan2(r, z) - math.atan2(L2 * math.sin(a_el), L1 + L2 * math.cos(a_el))
    return a_sh, a_el, math.pi - a_sh - a_el


def arm_pose(t, dz=0.0, dr=0.0, yaw_deg=-90.0):
    a, b, c = ik(dz, dr)
    for o, v in ((sh, a), (el, b), (wr, c)):
        o.rotation_euler.y = v
        o.keyframe_insert("rotation_euler", index=1, frame=F(t))
    yaw.rotation_euler.z = math.radians(yaw_deg)
    yaw.keyframe_insert("rotation_euler", index=2, frame=F(t))


# the cap the arm carries (placed like the hero's held cap, then parented to the wrist)
AS = 1.2
bpy.context.view_layer.update()
tip_world = OBJ["arm_tip"].matrix_world.translation.copy()
cap_base = tip_world.z - 0.07 - 0.62 * AS
held = K.lathe([(r * AS, h * AS) for r, h in K.CAP_PROFILE], (AX, 0, cap_base),
               P["graphite_lt"], segments=9, name="held_cap_anim")
held_stem = K.cyl(0.03 * AS, 0.12 * AS, (AX, 0, cap_base + 0.66 * AS), P["graphite_lt"],
                  verts=6, bevel=0, name="held_stem_anim")
HELD = K.empty((AX, 0, cap_base), name="held")
for o in (held, held_stem):
    K.set_parent(o, HELD)
K.set_parent(HELD, wr)

SPARKS = [o for o in OBJ if base(o) == "spark"]
for s in SPARKS:
    s.scale = (0, 0, 0)
WELD = OBJ["weld"]
WELD.data.energy = 0.0

# ------------------------------------------------------------------ gates: lamps, lasers, labels
gate_lamps = sorted([o for o in OBJ if base(o) == "gate_lamp"], key=lambda o: wloc(o).y)
gate_lasers = sorted([o for o in OBJ if base(o) == "gate_laser"], key=lambda o: wloc(o).y)
GATE = {}
FONT = None
for path in ("/System/Library/Fonts/SFNSMono.ttf", "/System/Library/Fonts/Menlo.ttc"):
    if os.path.exists(path):
        try:
            FONT = bpy.data.fonts.load(path)
            break
        except Exception:
            pass


def label(text, loc3, size, material, name, align="CENTER"):
    cu = bpy.data.curves.new(name, "FONT")
    cu.body = text
    cu.size = size
    cu.align_x = align
    cu.align_y = "BOTTOM"
    if FONT:
        cu.font = FONT
    o = bpy.data.objects.new(name, cu)
    bpy.context.collection.objects.link(o)
    o.location = loc3
    o.data.materials.append(material)
    return o


GX = 7.6
_az, _el = math.radians(-63), math.radians(36)
TOWARD_CAM = Vector((math.cos(_el) * math.cos(_az), math.cos(_el) * math.sin(_az), math.sin(_el)))
for i, (role, y) in enumerate(zip(ROLES, LANES)):
    lamp_mat, lamp_em = anim_emissive(f"lamp_{role}", (0.05, 0.08, 0.12, 1), 1.0)
    gate_lamps[i].data.materials.clear()
    gate_lamps[i].data.materials.append(lamp_mat)
    gate_lamps[i].scale = (1.6, 1.6, 1.4)
    light = K.point((GX, y - 0.45, Z + 1.9), RED, 0.0, radius=0.25, name=f"verdict_light_{role}")
    # "security  CHANGES" on one camera-facing line just above the gate beam: the role is
    # right-aligned at the gate, the verdict (a child, offset along camera-right) follows it.
    # Pushed towards the camera along the view axis: with an orthographic camera that leaves
    # the label's place on screen unchanged but keeps the gate posts from hiding it.
    name_mat = K.emissive(f"label_{role}", (0.55, 0.66, 0.80, 1), 1.5)
    lab = label(role, Vector((GX, y, Z + 2.05)) + TOWARD_CAM * 9.0, 0.40, name_mat,
                f"lab_{role}", align="RIGHT")
    pass_mat = K.emissive("verdict_pass", GREEN, 2.1)
    chg_mat = K.emissive("verdict_changes", RED, 2.3)
    v_pass = label("PASS", (0, 0, 0), 0.40, pass_mat, f"pass_{role}", align="LEFT")
    v_chg = label("CHANGES", (0, 0, 0), 0.40, chg_mat, f"changes_{role}", align="LEFT")
    for o in (v_pass, v_chg):
        o.parent = lab
        o.location = (0.28, 0, 0)
    for o in (v_pass, v_chg):
        o.scale = (0, 0, 0)
    GATE[role] = dict(y=y, lamp=lamp_em, laser=gate_lasers[i], light=light, label=lab,
                      PASS=v_pass, CHANGES=v_chg)

# ------------------------------------------------------------------ press, door, drafting
press = [o for o in OBJ if base(o) in ("stamp_head", "stamp_face", "piston")]
PRESS = K.empty((CX, 4.6, 0), name="press_rig")
for o in press:
    K.set_parent(o, PRESS)
stamp_light = OBJ["stamp_light"]
stamp_e0 = stamp_light.data.energy
door_mat = bpy.data.materials["warm_window"].node_tree.nodes["Emission"].inputs["Strength"]
door_spill = OBJ["door_spill"]
holo_light = OBJ["holo_light"]
holo_e0 = holo_light.data.energy

# ------------------------------------------------------------------ camera rig
TARGET = K.empty((0, 0, 0), name="cam_target")
cam_data = bpy.data.cameras.new("cam")
cam_data.type = "ORTHO"
cam_data.clip_end = 400
CAM = bpy.data.objects.new("cam", cam_data)
bpy.context.collection.objects.link(CAM)
EL_DEG, DIST = 36.0, 60.0
off = Vector((math.cos(math.radians(EL_DEG)), 0, math.sin(math.radians(EL_DEG)))) * DIST
CAM.parent = TARGET
CAM.location = off
CAM.rotation_euler = (-off).to_track_quat("-Z", "Y").to_euler()
scene.camera = CAM
for o in OBJ:
    if base(o).startswith("lab_"):
        c = o.constraints.new("COPY_ROTATION")
        c.target = CAM


def shot(t, target, scale, az):
    loc(TARGET, t, target)
    TARGET.rotation_euler = (0, 0, math.radians(az))
    TARGET.keyframe_insert("rotation_euler", index=2, frame=F(t))
    cam_data.ortho_scale = scale
    cam_data.keyframe_insert("ortho_scale", frame=F(t))


# ================================================================== the timeline (seconds)
TIMELINE = {}


def beat(name, t0, t1):
    TIMELINE[name] = [t0, t1]


Y0 = 0.0
# --- 0  title: wide establishing shot, the issue drifts in from the dark
shot(0.0, (4.5, 3.0, 1.0), 30.0, -63)
shot(4.2, (1.5, 2.2, 1.1), 26.0, -60)
loc(ISSUE, 0.0, (-13.0, Y0, Z))
loc(ISSUE, 4.4, (-8.4, Y0, Z))
beat("title", 0.0, 5.0)

# --- 1  spec-writer: the drafting station turns the issue into a spec
shot(6.4, (-3.3, 0.9, 1.5), 10.5, -56)
loc(ISSUE, 6.6, (-3.6, Y0, Z))
pop(HOLO, 0.0, False, 0.01)
pop(HOLO, 6.7, True, 0.6)
val(holo_light.data, "energy", 6.6, 0.0)
val(holo_light.data, "energy", 7.2, holo_e0 * 2.0)
pop(ISSUE, 7.7, False, 0.25)
loc(SPEC, 7.6, (-3.6, Y0, Z))
pop(SPEC, 7.7, True, 0.3)
pop(HOLO, 9.0, False, 0.4)
val(holo_light.data, "energy", 9.2, holo_e0 * 2.0)
val(holo_light.data, "energy", 9.6, holo_e0)
beat("spec", 6.2, 9.8)

# --- 2  builder: the arm fits the cap; the acorn turns molten
loc(SPEC, 9.0, (-3.6, Y0, Z))
shot(9.0, (-3.0, 0.9, 1.5), 10.5, -57)
shot(10.5, (0.9, 1.0, 1.8), 10.5, -61)
loc(SPEC, 10.6, (AX, Y0, Z))
arm_pose(0.0)
arm_pose(10.5)
arm_pose(11.3, dz=-0.42)
pop(SPEC, 11.3, False, 0.12)
loc(SOLID, 11.2, (AX, Y0, Z))
pop(SOLID, 11.3, True, 0.12)
pop(HELD, 0.0, True, 0.01)
pop(HELD, 11.3, False, 0.05)
arm_pose(11.6, dz=-0.42)
arm_pose(12.5)


def sparks(t0, t1):
    val(WELD.data, "energy", t0 - 0.05, 0.0)
    val(WELD.data, "energy", t0 + 0.1, 160.0)
    val(WELD.data, "energy", t1 - 0.2, 120.0)
    val(WELD.data, "energy", t1, 0.0)
    n = int((t1 - t0) * 12)
    for i, s in enumerate(SPARKS):
        scl(s, t0 - 0.05, 0.0)
        for k in range(n):
            tt = t0 + k / 12.0
            scl(s, tt, 1.6 if (k + i) % 3 else 0.0)
        scl(s, t1, 0.0)
        set_interp(s, "CONSTANT")


sparks(11.25, 11.9)
beat("build", 9.8, 13.6)

# --- 3  four reviewers in parallel, wait for all four
SPLIT_X = 3.9


def to_gates(t):
    """Main acorn rides to the splitter at t, splits into four, reaches the gates at t+1.4."""
    loc(SOLID, t - 1.0, (AX, Y0, Z))
    loc(SOLID, t, (SPLIT_X, Y0, Z))
    pop(SOLID, t, False, 0.15)
    for o, y in zip(LANE, LANES):
        loc(o, t - 0.05, (SPLIT_X, Y0, Z))
        pop(o, t, True, 0.15)
        loc(o, t + 0.7, (5.4, y, Z))
        loc(o, t + 1.4, (GX, y, Z))


def review(t0, ends, verdicts):
    """Gate scans from t0; each role resolves at ends[role] with verdicts[role]."""
    for role, g in GATE.items():
        em = g["lamp"]
        las = g["laser"]
        z0 = las.location.z
        loc(las, t0, (las.location.x, las.location.y, z0))
        tt, k = t0, 0
        while tt < ends[role] - 0.2:
            tt += 0.35
            loc(las, tt, (las.location.x, las.location.y, z0 + (0.85 if k % 2 == 0 else 0.0)))
            k += 1
        loc(las, ends[role], (las.location.x, las.location.y, z0))
        val(em.inputs["Color"], "default_value", t0, K.CYAN)
        val(em.inputs["Strength"], "default_value", t0, 3.0)
        col = GREEN if verdicts[role] == "PASS" else RED
        val(em.inputs["Color"], "default_value", ends[role] - 0.01, K.CYAN)
        val(em.inputs["Color"], "default_value", ends[role], col)
        val(em.inputs["Strength"], "default_value", ends[role], 6.0)
        pop(g[verdicts[role]], ends[role], True, 0.18)
        if verdicts[role] == "CHANGES":
            L = g["light"].data
            val(L, "energy", ends[role] - 0.01, 0.0)
            val(L, "energy", ends[role] + 0.08, 140.0)
            val(L, "energy", ends[role] + 0.5, 50.0)


def reset_gates(t, verdicts):
    for role, g in GATE.items():
        em = g["lamp"]
        val(em.inputs["Color"], "default_value", t, (0.05, 0.08, 0.12, 1))
        val(em.inputs["Strength"], "default_value", t, 1.0)
        pop(g[verdicts[role]], t, False, 0.2)
        if verdicts[role] == "CHANGES":
            val(g["light"].data, "energy", t, 0.0)


for g in GATE.values():
    val(g["lamp"].inputs["Color"], "default_value", 0.0, (0.05, 0.08, 0.12, 1))
    val(g["lamp"].inputs["Strength"], "default_value", 0.0, 1.0)
    val(g["light"].data, "energy", 0.0, 0.0)
    for v in ("PASS", "CHANGES"):
        scl(g[v], 0.0, 0.0)
    scl(g["label"], 0.0, 0.0)
    pop(g["label"], 13.9, True, 0.4)

loc(SOLID, 12.6, (AX, Y0, Z))
to_gates(13.6)
shot(12.6, (1.5, 1.0, 1.8), 10.5, -61)
shot(14.4, (6.9, 0.2, 1.6), 12.0, -64)
V1 = {"security": "CHANGES", "ux": "PASS", "ui-design": "PASS", "code": "PASS"}
E1 = {"ui-design": 16.4, "ux": 16.9, "code": 17.4, "security": 18.1}
review(15.0, E1, V1)
beat("review", 13.6, 18.0)
beat("changes", 18.0, 20.4)

# --- 4  CHANGES: back to the builder (the same conversation resumes), fix round 1
shot(19.2, (6.6, 0.2, 1.6), 12.2, -64)
for o, y in zip(LANE, LANES):
    loc(o, 19.4, (GX, y, Z))
    loc(o, 20.0, (5.4, y, Z))
    loc(o, 20.5, (SPLIT_X, Y0, Z))
    pop(o, 20.45, False, 0.12)
reset_gates(20.0, V1)
loc(SOLID, 20.45, (SPLIT_X, Y0, Z))
pop(SOLID, 20.45, True, 0.12)
val(REWORK.data, "energy", 20.4, 0.0)
val(REWORK.data, "energy", 20.7, 45.0)
loc(SOLID, 21.5, (AX, Y0, Z))
shot(21.2, (2.4, 0.8, 1.7), 11.5, -62)
arm_pose(21.4)
arm_pose(21.9, dz=-0.30)
sparks(21.95, 22.7)
val(REWORK.data, "energy", 22.5, 45.0)
val(REWORK.data, "energy", 22.9, 0.0)
arm_pose(22.8, dz=-0.30)
arm_pose(23.4)
pop(HELD, 26.0, True, 0.3)        # a fresh cap, out of shot
beat("fix", 20.4, 24.0)

# --- 5  second review: all four PASS
loc(SOLID, 23.0, (AX, Y0, Z))
to_gates(24.0)
shot(23.2, (2.6, 0.8, 1.7), 11.5, -62)
shot(24.8, (6.9, 0.2, 1.6), 12.0, -64)
V2 = {r: "PASS" for r in ROLES}
E2 = {"ui-design": 25.8, "ux": 26.1, "code": 26.4, "security": 26.7}
review(25.4, E2, V2)
beat("pass", 25.4, 28.0)

# --- 6  merge into one, turn the corner, approver stamps the seal
for o, y in zip(LANE, LANES):
    loc(o, 27.3, (GX, y, Z))
    loc(o, 27.9, (9.6, y, Z))
    loc(o, 28.4, (11.3, Y0, Z))
    pop(o, 28.35, False, 0.12)
reset_gates(28.6, V2)
loc(SOLID, 28.3, (11.3, Y0, Z))
pop(SOLID, 28.35, True, 0.12)
loc(SOLID, 29.0, (CX, Y0, Z))
rot(SOLID, 29.0, (0, 0, 0))
rot(SOLID, 29.4, (0, 0, math.radians(90)))
loc(SOLID, 29.4, (CX, Y0, Z))
loc(SOLID, 30.3, (CX, 4.6, Z))
shot(27.6, (8.8, 0.8, 1.6), 12.5, -63)
shot(29.6, (13.2, 3.6, 2.0), 10.5, -58)
scl(SEAL, 0.0, 0.0)
loc(PRESS, 0.0, (CX, 4.6, 0))
loc(PRESS, 28.8, (CX, 4.6, 0))
loc(PRESS, 29.6, (CX, 4.6, 0.55))
loc(PRESS, 30.5, (CX, 4.6, 0.55))
loc(PRESS, 30.75, (CX, 4.6, -0.08))
pop(SEAL, 30.72, True, 0.08)
loc(PRESS, 31.1, (CX, 4.6, -0.08))
loc(PRESS, 31.7, (CX, 4.6, 0.55))
val(stamp_light.data, "energy", 30.7, stamp_e0)
val(stamp_light.data, "energy", 30.8, stamp_e0 * 6)
val(stamp_light.data, "energy", 31.5, stamp_e0)
beat("approve", 28.6, 32.2)

# --- 7  ship: merge to main
loc(SOLID, 31.4, (CX, 4.6, Z))
loc(SOLID, 32.4, (CX, 9.2, Z + 0.06))
loc(SOLID, 34.2, (CX, 14.4, Z + 0.06))
pop(SOLID, 34.15, False, 0.4)
val(door_mat, "default_value", 32.0, 0.55)
val(door_mat, "default_value", 34.0, 1.6)
val(door_spill.data, "energy", 32.0, door_spill.data.energy)
val(door_spill.data, "energy", 34.0, door_spill.data.energy * 2.2)
shot(31.6, (13.6, 6.0, 2.0), 11.0, -58)
shot(35.0, (13.4, 10.5, 2.0), 14.0, -55)
beat("ship", 32.2, 35.0)

# hologram turns slowly the whole time
rot(HOLO, 0.0, (0, 0, 0))
rot(HOLO, DURATION, (0, 0, math.radians(200)))
set_interp(HOLO, "LINEAR")

# ------------------------------------------------------------------ render settings
scene.render.fps = FPS
scene.frame_start = 1
scene.frame_end = F(DURATION) - 1
scene.frame_step = STEP
if "--frames" in ARGS:
    a, b = arg("--frames").split("-")
    scene.frame_start, scene.frame_end = int(a), int(b)
scene.render.resolution_x = 1920
scene.render.resolution_y = 1080
scene.render.resolution_percentage = 50 if PREVIEW else 100
scene.eevee.taa_render_samples = 12 if PREVIEW else int(arg("--samples", "48"))
scene.render.use_overwrite = False
scene.render.use_placeholder = True
os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "timeline.json"), "w") as fh:
    json.dump({"fps": FPS, "duration": DURATION, "frames": scene.frame_end, "beats": TIMELINE}, fh,
              indent=1)

if "--still" in ARGS:
    for f in (int(x) for x in arg("--still").split(",")):
        scene.frame_set(f)
        scene.render.filepath = os.path.join(OUT, f"still_{f:04d}.png")
        bpy.ops.render.render(write_still=True)
else:
    scene.render.filepath = os.path.join(OUT, "")
    bpy.ops.render.render(animation=True)
print("DONE", OUT)
