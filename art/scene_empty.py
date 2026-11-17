"""Empty-state scene: an idle conveyor with a single robot arm at rest.

Render:  Blender -b -P art/scene_empty.py            (800x600)
Output:  art/build/empty_raw.png (RGBA, transparent background)
Then:    python3 art/post.py empty
"""
import math
import os
import sys

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import factory_kit as K  # noqa: E402

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
PREVIEW = "--preview" in ARGS

scene = K.reset_scene()
P = K.palette()
P["floorline"] = K.emissive("floorline", K.AMBER, 0.45)
P["nut_idle"] = K.emissive("nut_idle", (1.0, 0.30, 0.03, 1), 0.9)
Z = K.BELT_Z

K.box((80, 80, 0.2), (0, 0, -0.1), P["floor"], bevel=0, name="floor")
for i in range(14):
    K.box((0.7, 0.07, 0.01), (-8 + i * 1.25, -1.9, 0.005), P["floorline"], bevel=0, name="dash")

# the idle line: belt with empty carriers
K.conveyor(-7.0, 4.2, 0.0, P, edge="amber_soft")
for x in (-4.6, -2.6, 1.6, 3.4):
    K.cyl(0.32, 0.084, (x, 0, Z + 0.042), P["graphite"], verts=16, bevel=0.01, name="carrier")
    K.cyl(0.156, 0.036, (x, 0, Z + 0.096), P["steel"], verts=16, bevel=0, name="carrier_cup")

# the arm, folded and parked, its lights dimmed
ped_h = 0.45
ax, ay = -0.6, 1.75
K.box((1.4, 1.4, ped_h), (ax, ay, ped_h / 2), P["blueblack"], bevel=0.04, name="arm_pedestal")
K.box((1.42, 1.42, 0.04), (ax, ay, ped_h - 0.08), P["amber_soft"], bevel=0, name="ped_band")
K.robot_arm((ax, ay, ped_h), P, yaw=math.radians(-35), shoulder=math.radians(-32),
            elbow=math.radians(148), wrist=math.radians(70), scale=1.15, grip_open=0.12)

# one acorn left on the belt, quietly glowing: the signature detail
s = 1.2
x0 = -0.6
K.cyl(0.27 * s, 0.07 * s, (x0, 0, Z + 0.035 * s), P["graphite"], verts=16, bevel=0.01)
K.cyl(0.13 * s, 0.03 * s, (x0, 0, Z + 0.08 * s), P["steel"], verts=16, bevel=0)
base = Z + 0.05 * s
K.lathe([(r * s, h * s) for r, h in K.NUT_PROFILE], (x0, 0, base), P["nut_idle"], segments=12,
        name="nut")
K.lathe([(r * s, h * s) for r, h in K.CAP_PROFILE], (x0, 0, base), P["graphite_lt"], segments=9,
        name="cap")
K.cyl(0.03 * s, 0.12 * s, (x0, 0, base + 0.66 * s), P["graphite_lt"], verts=6, bevel=0)
K.point((x0, -0.4, Z + 0.5), K.EMBER, 25, radius=0.2, name="acorn_glow")

# a small status post at the end of the line, standing by (cyan)
K.box((0.3, 0.3, 1.9), (3.9, -1.35, 0.95), P["graphite"], bevel=0.04, name="status_post")
K.box((0.32, 0.32, 0.05), (3.9, -1.35, 1.75), P["cyan"], bevel=0, name="status_ring")

K.sun((math.radians(55), 0, math.radians(150)), (0.55, 0.7, 1.0, 1), 2.6, angle=0.08,
      name="moon_rim")
K.sun((math.radians(60), 0, math.radians(-40)), (0.5, 0.6, 0.9, 1), 0.45, angle=0.3,
      name="fill")
K.area((-0.6, 0.3, 4.5), (0, 0, 0), (1.0, 0.8, 0.6, 1), 120, size=3.0, name="work_light")

K.iso_camera((-0.3, 0.6, 1.3), azimuth_deg=-58, elevation_deg=33, ortho_scale=10.0)
scene.render.resolution_x = 800
scene.render.resolution_y = 600
scene.render.resolution_percentage = 100
scene.eevee.taa_render_samples = 32 if PREVIEW else 128
out = os.path.join(HERE, "build", "empty_raw_preview.png" if PREVIEW else "empty_raw.png")
os.makedirs(os.path.dirname(out), exist_ok=True)
scene.render.filepath = out
bpy.ops.render.render(write_still=True)
print("WROTE", out)
