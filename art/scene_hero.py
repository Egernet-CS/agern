"""Hero scene: the Agern software factory at night.

The line runs as a "V" (a check mark): specs come in from the dark at the upper
left, get drafted, assembled by a robot arm, pass four parallel review gates,
turn the corner, get the approval stamp and leave through the shipping dock at
the upper right. The products on the belt are acorn-shaped capsules.

Render:  Blender -b -P art/scene_hero.py            (full 2400x1200)
         Blender -b -P art/scene_hero.py -- --preview   (50 %, fewer samples)
Output:  art/build/hero_raw.png (RGBA, transparent background)
Then:    python3 art/post.py hero
"""
import math
import os
import sys

import bpy
from mathutils import Matrix, Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import factory_kit as K  # noqa: E402

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
PREVIEW = "--preview" in ARGS

scene = K.reset_scene()
P = K.palette()
P["floorline"] = K.emissive("floorline", K.AMBER, 0.45)
P["warm_window"] = K.emissive("warm_window", (1.0, 0.5, 0.18, 1), 0.55)

Z = K.BELT_Z


def dashes(x0, x1, y, step=1.25):
    n = int((x1 - x0) / step)
    for i in range(n):
        K.box((0.7, 0.07, 0.01), (x0 + i * step, y, 0.005), P["floorline"], bevel=0,
              name="dash")


def pole_lamp(x, y, h=4.2, reach=1.6, power=160):
    K.cyl(0.08, h, (x, y, h / 2), P["graphite_lt"], verts=10, name="pole")
    K.box((0.1, reach, 0.1), (x, y - reach / 2, h), P["graphite_lt"], bevel=0.02,
          name="pole_arm")
    K.box((0.6, 0.35, 0.12), (x, y - reach, h - 0.05), P["graphite"], bevel=0.03,
          name="lamp_shade")
    K.box((0.5, 0.26, 0.02), (x, y - reach, h - 0.12), P["white"], bevel=0, name="lamp_face")
    K.area((x, y - reach, h - 0.2), (0, 0, 0), (1.0, 0.78, 0.55, 1), power, size=0.6,
           name="pole_light")


# ================================================================== floor
K.box((200, 200, 0.2), (8, 4, -0.1), P["floor"], bevel=0, name="floor")

# ================================================================== segment A (along +X)
dashes(-14, 12.4, -3.3)
dashes(-14, 10.5, 3.4)

K.conveyor(-12.0, 3.6, 0.0, P)                      # intake -> drafting -> assembly
K.prism([(3.6, -0.75), (5.3, -2.85), (5.3, 2.85), (3.6, 0.75)], Z - 0.2, Z - 0.02,
        P["graphite"], name="splitter")
K.prism([(9.6, -2.85), (11.3, -0.75), (11.3, 0.75), (9.6, 2.85)], Z - 0.2, Z - 0.02,
        P["graphite"], name="merger")
for deck_x in (4.45, 10.45):
    K.box((0.9, 0.5, Z - 0.2), (deck_x, 0, (Z - 0.2) / 2), P["blueblack"], bevel=0.03,
          name="deck_leg")
LANES = (-2.1, -0.7, 0.7, 2.1)
for y in LANES:
    K.conveyor(5.3, 9.6, y, P, width=0.95, edge="cyan_soft")
K.conveyor(11.3, 13.0, 0.0, P)

# ---------------------------------------------------------------- 1. drafting
dx = -3.6
K.box((2.6, 1.3, 0.08), (dx, 1.85, 1.05), P["graphite_lt"], bevel=0.02, name="desk_top")
for sx in (-1.15, 1.15):
    K.box((0.1, 1.1, 1.0), (dx + sx, 1.85, 0.5), P["graphite"], bevel=0.02, name="desk_leg")
board = K.box((2.3, 0.08, 1.5), (dx, 2.05, 1.95), P["blueblack"], bevel=0.03,
              rot=(math.radians(-18), 0, 0), name="board")
for i in range(6):
    K.local_box((1.9 - (i % 3) * 0.4, 0.01, 0.025), (-(i % 3) * 0.2, -0.05, -0.5 + i * 0.19),
                P["cyan"] if i % 2 == 0 else P["cyan_soft"], board, bevel=0, name="bp_line")
for i in range(3):
    K.local_box((0.025, 0.01, 1.1), (-0.8 + i * 0.75, -0.05, 0.0), P["cyan_soft"], board,
                bevel=0, name="bp_col")
# a small acorn elevation sketched on the board
sketch = K.lathe([(r * 1.1, h * 1.1) for r, h in K.NUT_PROFILE + K.CAP_PROFILE[1:]],
                 (0, 0, 0), P["cyan"], segments=10, name="bp_acorn")
sketch.modifiers.new("wire", "WIREFRAME").thickness = 0.012
sketch.parent = board
sketch.location = (0.62, -0.25, -0.35)
K.cyl(0.05, 1.6, (dx - 1.0, 2.45, 2.0), P["steel"], verts=8, name="lamp_post")
K.box((1.1, 0.07, 0.07), (dx - 0.55, 2.25, 2.8), P["steel"], bevel=0.01,
      rot=(0, math.radians(-12), math.radians(-25)), name="lamp_arm")
K.cyl(0.18, 0.14, (dx - 0.05, 2.0, 2.9), P["graphite"], verts=16, name="lamp_head")
K.cyl(0.15, 0.02, (dx - 0.05, 2.0, 2.82), P["white"], verts=16, bevel=0, name="lamp_bulb")
K.area((dx - 0.05, 2.0, 2.75), (0, 0, 0), (1.0, 0.85, 0.7, 1), 50, size=0.4, name="desk_light")
# hologram projector: the spec takes shape above the belt
K.acorn((dx, 0.0, Z + 0.35), P, kind="wire", size=1.5, carrier=False)
K.cyl(0.5, 0.03, (dx, 0.0, Z + 0.02), P["cyan_soft"], verts=32, bevel=0, name="holo_pad")
K.point((dx, -0.3, Z + 1.0), K.CYAN, 70, radius=0.3, name="holo_light")
# specs drifting in from the dark
for x in (-10.4, -8.2, -6.0):
    K.acorn((x, 0, Z), P, kind="wire", size=1.2)
K.acorn((-1.5, 0, Z), P, kind="wire", size=1.2)

# ---------------------------------------------------------------- 2. assembly
ax = 1.2
ARM_S = 1.15
SH, EL, WR = math.radians(10), math.radians(110), math.radians(60)
reach = ARM_S * (1.75 * math.sin(SH) + 1.45 * math.sin(SH + EL)
                 + 0.62 * math.sin(SH + EL + WR))
tip_z = ARM_S * (0.92 + 1.75 * math.cos(SH) + 1.45 * math.cos(SH + EL)
                 + 0.62 * math.cos(SH + EL + WR))
ped_h = 0.45
K.box((1.4, 1.4, ped_h), (ax, reach, ped_h / 2), P["blueblack"], bevel=0.04,
      name="arm_pedestal")
K.box((1.42, 1.42, 0.04), (ax, reach, ped_h - 0.08), P["amber_soft"], bevel=0,
      name="pedestal_band")
K.robot_arm((ax, reach, ped_h), P, yaw=math.radians(-90), shoulder=SH, elbow=EL, wrist=WR,
            scale=ARM_S, grip_open=0.2)
AS = 1.2
K.acorn((ax, 0, Z), P, kind="bare", size=AS)
cap_base = ped_h + tip_z - 0.07 - 0.62 * AS
K.lathe([(r * AS, h * AS) for r, h in K.CAP_PROFILE], (ax, 0, cap_base), P["graphite_lt"],
        segments=9, name="held_cap")
K.cyl(0.03 * AS, 0.12 * AS, (ax, 0, cap_base + 0.66 * AS), P["graphite_lt"], verts=6, bevel=0)
K.point((ax, -0.35, Z + 0.5), K.EMBER, 80, radius=0.15, name="weld")
for ox, oy, oz in [(0.22, -0.25, 0.42), (-0.2, -0.3, 0.5), (0.3, 0.1, 0.58),
                   (-0.3, 0.05, 0.36), (0.08, -0.36, 0.62), (0.36, -0.15, 0.3)]:
    K.box((0.03, 0.03, 0.03), (ax + ox, oy, Z + oz), P["white"], bevel=0, name="spark")
K.acorn((2.9, 0, Z), P, kind="solid", size=1.2)

# ---------------------------------------------------------------- 3. four review gates
gx = 7.6
for i, y in enumerate(LANES):
    for s in (-1, 1):
        K.box((0.16, 0.16, 1.55), (gx, y + s * 0.62, Z - 0.1 + 0.78), P["graphite_lt"],
              bevel=0.03, name="gate_post")
    K.box((0.34, 1.45, 0.24), (gx, y, Z + 1.55), P["graphite"], bevel=0.04, name="gate_beam")
    K.box((0.06, 1.1, 0.03), (gx, y, Z + 1.42), P["cyan"], bevel=0, name="gate_bar")
    for s in (-1, 1):
        K.box((0.03, 0.02, 1.2), (gx, y + s * 0.535, Z + 0.7), P["cyan_soft"], bevel=0,
              name="gate_post_glow")
    K.box((0.5, 0.9, 0.006), (gx, y, Z + 0.004), P["cyan_faint"], bevel=0, name="gate_floor")
    K.box((0.025, 1.12, 0.025), (gx, y, Z + 0.42), P["cyan"], bevel=0, name="gate_laser")
    K.cyl(0.08, 0.1, (gx, y + 0.5, Z + 1.72), P["cyan"] if i == 2 else P["amber"], verts=16,
          bevel=0, name="gate_lamp")
    K.point((gx, y - 0.2, Z + 1.1), K.CYAN, 25, radius=0.2, name="gate_light")
    off = (0.0, 0.7, 0.35, 1.0)[i]
    K.acorn((6.0 + off * 0.45, y, Z), P, kind="solid", size=0.95)
    K.acorn((gx, y, Z), P, kind="solid", size=0.95)
    if i % 2 == 0:
        K.acorn((9.1 - off * 0.3, y, Z), P, kind="solid", size=0.95)
K.box((0.25, 5.4, 0.12), (gx, 0, Z + 1.75), P["blueblack"], bevel=0.02, name="gate_tray")
K.acorn((12.2, 0, Z), P, kind="solid", size=1.2)

# ---------------------------------------------------------------- corner turntable
CX = 13.9
K.cyl(1.0, Z - 0.05, (CX, 0, (Z - 0.05) / 2), P["graphite"], verts=32, bevel=0.03,
      name="turntable_base")
K.cyl(0.92, 0.08, (CX, 0, Z - 0.04), P["rubber"], verts=32, bevel=0.01, name="turntable")
K.torus(0.98, 0.02, (CX, 0, Z - 0.03), P["amber_soft"], name="turntable_ring")
K.acorn((CX, 0, Z), P, kind="solid", size=1.2)

for i, y in enumerate((5.0, 5.35)):
    K.cyl(0.12, 24, (-1.0, y, 4.6 + i * 0.25), P["graphite_lt"], rot=(0, math.pi / 2, 0),
          verts=16, name="pipe")
for x in (-8.0, -1.0, 6.0):
    K.box((0.25, 0.25, 4.9), (x, 5.2, 2.45), P["graphite"], bevel=0.03, name="pipe_post")

# ---------------------------------------------------------------- server racks (the agents)
import random  # noqa: E402
rnd = random.Random(4)


def rack(x, y, h=2.3):
    K.box((0.9, 0.75, h), (x, y, h / 2), P["blueblack"], bevel=0.04, name="rack")
    K.box((0.92, 0.77, 0.05), (x, y, h - 0.15), P["graphite_lt"], bevel=0, name="rack_cap")
    for row in range(9):
        zz = 0.35 + row * (h - 0.7) / 8
        K.box((0.72, 0.02, 0.012), (x, y - 0.38, zz - 0.07), P["graphite_lt"], bevel=0,
              name="rack_slot")
        for col in range(5):
            if rnd.random() < 0.55:
                m = P["amber"] if rnd.random() < 0.7 else P["cyan"]
                K.box((0.05, 0.02, 0.03), (x - 0.26 + col * 0.13, y - 0.385, zz), m, bevel=0,
                      name="led")


for i, x in enumerate((6.4, 7.4, 8.4, 9.4)):
    rack(x, 5.6, h=2.3 if i % 3 else 2.0)
K.box((4.4, 0.05, 0.03), (7.9, 5.2, 0.01), P["cyan_soft"], bevel=0, name="rack_floor_line")
K.point((7.9, 4.6, 1.6), K.AMBER, 40, radius=0.5, name="rack_glow")

# ================================================================== segment B
# Built along +X in local space, then swung 90 degrees to run along +Y from the corner.
before = set(bpy.data.objects)

dashes(1.6, 9.0, -1.6)
K.conveyor(0.95, 9.2, 0.0, P)

sx = 4.6
for s in (-1, 1):
    K.box((0.4, 0.4, 3.3), (sx, s * 1.15, 1.65), P["graphite"], bevel=0.05, name="press_col")
    K.box((0.06, 0.06, 2.4), (sx - 0.21, s * 1.15, 1.6), P["amber_soft"], bevel=0,
          name="press_col_line")
K.box((0.8, 2.9, 0.5), (sx, 0, 3.45), P["graphite_lt"], bevel=0.06, name="press_beam")
K.box((0.82, 0.3, 0.06), (sx, -1.0, 3.45), P["amber"], bevel=0, name="press_badge")
K.cyl(0.2, 1.0, (sx, 0, 2.75), P["steel"], verts=20, name="piston")
K.box((0.85, 0.85, 0.32), (sx, 0, 2.1), P["graphite_lt"], bevel=0.05, name="stamp_head")
K.box((0.7, 0.7, 0.04), (sx, 0, 1.93), P["amber"], bevel=0, name="stamp_face")
K.point((sx, -0.2, 1.75), K.AMBER, 90, radius=0.3, name="stamp_light")
K.acorn((2.6, 0, Z), P, kind="solid", size=1.2)
K.acorn((sx, 0, Z), P, kind="solid", size=1.2, seal=True)
K.acorn((6.6, 0, Z), P, kind="solid", size=1.2, seal=True)
K.acorn((8.5, 0, Z), P, kind="solid", size=1.2, seal=True)

# shipping dock
d0, d1 = 9.2, 15.4
K.box((d1 - d0, 7.0, Z - 0.02), ((d0 + d1) / 2, 0.0, (Z - 0.02) / 2), P["graphite"],
      bevel=0.05, name="dock")
K.box((0.04, 7.02, 0.05), (d0 - 0.01, 0.0, Z - 0.12), P["amber_soft"], bevel=0,
      name="dock_edge")
K.box((d1 - d0 + 0.02, 0.04, 0.05), ((d0 + d1) / 2, -3.51, Z - 0.12), P["amber_soft"],
      bevel=0, name="dock_edge2")
K.box((0.35, 7.0, 4.4), (d1 - 0.2, 0.0, Z + 2.2), P["blueblack"], bevel=0.04, name="dock_wall")
K.box((0.1, 2.8, 2.4), (d1 - 0.42, 0.0, Z + 1.2), P["warm_window"], bevel=0, name="door_glow")
for i in range(6):
    K.box((0.14, 2.9, 0.12), (d1 - 0.48, 0.0, Z + 2.45 + i * 0.13), P["graphite_lt"],
          bevel=0.02, name="door_slat")
K.box((0.2, 3.2, 0.12), (d1 - 0.5, 0.0, Z + 3.3), P["amber"], bevel=0, name="door_header")
K.area((d1 - 1.4, 0.0, Z + 1.4), (0, math.radians(-90), 0), (1.0, 0.62, 0.3, 1), 120,
       size=2.4, name="door_spill")
K.box((0.06, 5.5, 0.06), (d1 - 0.42, 0.0, Z + 4.0), P["cyan_soft"], bevel=0, name="wall_line")
# the conveyor carries on along the dock floor to the door
K.box((d1 - d0 - 0.6, 0.9, 0.06), ((d0 + d1) / 2 - 0.3, 0.0, Z + 0.03), P["rubber"], bevel=0.01,
      name="dock_track")
for s in (-1, 1):
    K.box((d1 - d0 - 0.6, 0.03, 0.02), ((d0 + d1) / 2 - 0.3, s * 0.5, Z + 0.05), P["amber_soft"],
          bevel=0, name="dock_track_line")
K.acorn((10.6, 0, Z + 0.06), P, kind="solid", size=1.2, seal=True)
K.acorn((12.6, 0, Z + 0.06), P, kind="solid", size=1.2, seal=True)


def pallet(x, y, n_high, rz=0.0):
    K.box((1.05, 1.05, 0.12), (x, y, Z + 0.06), P["blueblack"], bevel=0.02, rot=(0, 0, rz),
          name="pallet")
    for k in range(n_high):
        K.feature_block((x, y, Z + 0.12 + k * 0.82), P, kind="crate", size=0.8, rot_z=rz)


pallet(10.4, 2.3, 2)
pallet(11.6, 2.3, 1, math.radians(4))
pallet(13.4, 2.4, 3)
pallet(13.4, -2.2, 2, math.radians(-3))
pallet(11.9, -2.4, 1, math.radians(5))
for x in (10.0, 11.6, 13.2, 14.6):
    K.cyl(0.12, 0.4, (x, -3.25, Z + 0.2), P["graphite_lt"], verts=12, name="bollard")
    K.cyl(0.125, 0.05, (x, -3.25, Z + 0.33), P["amber"], verts=12, bevel=0, name="bollard_ring")

# swing everything built for segment B around the corner
swing = Matrix.Translation((CX, 0, 0)) @ Matrix.Rotation(math.pi / 2, 4, "Z")
for ob in set(bpy.data.objects) - before:
    if ob.parent is None:
        ob.matrix_world = swing @ ob.matrix_world

# ================================================================== global light
K.sun((math.radians(55), 0, math.radians(150)), (0.55, 0.7, 1.0, 1), 2.6, angle=0.08,
      name="moon_rim")
K.sun((math.radians(60), 0, math.radians(-40)), (0.5, 0.6, 0.9, 1), 0.45, angle=0.3,
      name="fill")

# ================================================================== camera & render
K.iso_camera((6.5, 5.0, 1.0), azimuth_deg=-62, elevation_deg=36, ortho_scale=29.0,
             shift=(-0.04, 0.0))
scene.render.resolution_x = 2400
scene.render.resolution_y = 1200
scene.render.resolution_percentage = 50 if PREVIEW else 100
if PREVIEW:
    scene.eevee.taa_render_samples = 24
out = os.path.join(HERE, "build", "hero_raw_preview.png" if PREVIEW else "hero_raw.png")
os.makedirs(os.path.dirname(out), exist_ok=True)
scene.render.filepath = out
bpy.ops.render.render(write_still=True)
print("WROTE", out)
