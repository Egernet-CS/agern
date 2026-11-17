"""Shared building blocks for the Software Factory scenes (Blender 5.2, bpy).

Everything is built procedurally from primitives so renders are reproducible.
Units: 1 = roughly half a metre. Conveyor runs along +X, Z is up.
"""
import math

import bpy
from mathutils import Vector

# ---------------------------------------------------------------- colours

def srgb(h):
    """'#ffb347' -> linear RGBA tuple."""
    h = h.lstrip("#")
    out = []
    for i in (0, 2, 4):
        c = int(h[i:i + 2], 16) / 255.0
        out.append(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4)
    return (*out, 1.0)


AMBER = srgb("#ffb347")
EMBER = srgb("#ff8a3d")
CYAN = srgb("#4fd6ff")
BG_HEX = "#0b0e14"

# ---------------------------------------------------------------- scene

def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    ee = scene.eevee
    ee.taa_render_samples = 96
    ee.use_raytracing = True
    ee.use_shadows = True
    ee.shadow_ray_count = 2
    ee.shadow_step_count = 8
    ee.use_fast_gi = True
    ee.clamp_surface_indirect = 10.0
    scene.render.film_transparent = True
    scene.view_settings.view_transform = "AgX"
    scene.view_settings.exposure = 0.75
    try:
        scene.view_settings.look = "AgX - Punchy"
    except TypeError:
        pass
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.image_settings.color_depth = "8"
    world = bpy.data.worlds.new("World")
    scene.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    bg.inputs["Color"].default_value = (0.010, 0.014, 0.026, 1.0)
    bg.inputs["Strength"].default_value = 1.0
    return scene


# ---------------------------------------------------------------- materials

_MATS = {}


def mat(name, color=(0.05, 0.05, 0.05, 1), rough=0.5, metal=0.0,
        emit=None, strength=0.0, coat=0.0, alpha=1.0):
    key = name
    if key in _MATS:
        return _MATS[key]
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    p = m.node_tree.nodes.get("Principled BSDF")
    p.inputs["Base Color"].default_value = color
    p.inputs["Roughness"].default_value = rough
    p.inputs["Metallic"].default_value = metal
    p.inputs["Coat Weight"].default_value = coat
    if emit is not None:
        p.inputs["Emission Color"].default_value = emit
        p.inputs["Emission Strength"].default_value = strength
    if alpha < 1.0:
        p.inputs["Alpha"].default_value = alpha
        try:
            m.surface_render_method = "BLENDED"
        except Exception:
            pass
    _MATS[key] = m
    return m


def emissive(name, color, strength):
    """Pure emission material (no BSDF) for clean glowing parts."""
    if name in _MATS:
        return _MATS[name]
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.remove(nt.nodes.get("Principled BSDF"))
    e = nt.nodes.new("ShaderNodeEmission")
    e.inputs["Color"].default_value = color
    e.inputs["Strength"].default_value = strength
    nt.links.new(e.outputs[0], nt.nodes.get("Material Output").inputs["Surface"])
    _MATS[name] = m
    return m


def ghost(name, color, strength, opacity):
    """Additive-looking translucent emission (emission mixed with transparency)."""
    if name in _MATS:
        return _MATS[name]
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    try:
        m.surface_render_method = "BLENDED"
    except Exception:
        pass
    nt = m.node_tree
    nt.nodes.remove(nt.nodes.get("Principled BSDF"))
    e = nt.nodes.new("ShaderNodeEmission")
    e.inputs["Color"].default_value = color
    e.inputs["Strength"].default_value = strength
    t = nt.nodes.new("ShaderNodeBsdfTransparent")
    mix = nt.nodes.new("ShaderNodeMixShader")
    mix.inputs[0].default_value = opacity
    nt.links.new(t.outputs[0], mix.inputs[1])
    nt.links.new(e.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], nt.nodes.get("Material Output").inputs["Surface"])
    _MATS[name] = m
    return m


def palette():
    return {
        "graphite": mat("graphite", (0.030, 0.034, 0.042, 1), 0.42, 0.35),
        "graphite_lt": mat("graphite_lt", (0.060, 0.066, 0.080, 1), 0.38, 0.5),
        "blueblack": mat("blueblack", (0.010, 0.014, 0.024, 1), 0.55, 0.1),
        "steel": mat("steel", (0.16, 0.17, 0.19, 1), 0.28, 0.9),
        "rubber": mat("rubber", (0.008, 0.009, 0.012, 1), 0.75, 0.0),
        "floor": mat("floor", (0.016, 0.019, 0.027, 1), 0.5, 0.0),
        "trim": mat("trim", (0.10, 0.07, 0.04, 1), 0.35, 0.8),
        "amber": emissive("amber", AMBER, 2.6),
        "nut": emissive("nut", (1.0, 0.30, 0.03, 1), 1.7),
        "amber_soft": emissive("amber_soft", AMBER, 2.2),
        "ember": emissive("ember", EMBER, 3.0),
        "cyan": emissive("cyan", CYAN, 3.0),
        "cyan_soft": emissive("cyan_soft", CYAN, 1.2),
        "cyan_faint": emissive("cyan_faint", CYAN, 0.35),
        "white": emissive("white_hot", (1.0, 0.86, 0.66, 1), 5.0),
        "scan": ghost("scan", CYAN, 1.0, 0.035),
        "glass": mat("glass_amber", (0.6, 0.3, 0.05, 1), 0.15, 0.0,
                     emit=AMBER, strength=1.4, alpha=0.55),
    }


# ---------------------------------------------------------------- primitives

def _finish(obj, material, bevel, segments=2):
    if material is not None:
        obj.data.materials.append(material)
    if bevel > 0:
        mod = obj.modifiers.new("bevel", "BEVEL")
        mod.width = bevel
        mod.segments = segments
        mod.limit_method = "ANGLE"
    for poly in obj.data.polygons:
        poly.use_smooth = False
    return obj


def box(size, loc, material=None, bevel=0.03, rot=(0, 0, 0), name="box", parent=None):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc, rotation=rot)
    o = bpy.context.active_object
    o.name = name
    o.scale = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    _finish(o, material, bevel)
    if parent is not None:
        set_parent(o, parent)
    return o


def cyl(radius, depth, loc, material=None, rot=(0, 0, 0), verts=24, bevel=0.02,
        name="cyl", parent=None):
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=radius, depth=depth,
                                        location=loc, rotation=rot)
    o = bpy.context.active_object
    o.name = name
    _finish(o, material, bevel)
    if parent is not None:
        set_parent(o, parent)
    return o


def torus(major, minor, loc, material=None, rot=(0, 0, 0), name="torus"):
    bpy.ops.mesh.primitive_torus_add(major_radius=major, minor_radius=minor,
                                     major_segments=32, minor_segments=8,
                                     location=loc, rotation=rot)
    o = bpy.context.active_object
    o.name = name
    _finish(o, material, 0)
    return o


def prism(points2d, z0, z1, material=None, name="prism", bevel=0.02):
    """Extrude a convex 2D polygon (XY) between z0 and z1."""
    n = len(points2d)
    verts = [(x, y, z0) for x, y in points2d] + [(x, y, z1) for x, y in points2d]
    faces = [list(range(n))[::-1], list(range(n, 2 * n))]
    for i in range(n):
        j = (i + 1) % n
        faces.append([i, j, n + j, n + i])
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    me.update()
    o = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(o)
    return _finish(o, material, bevel)


def lathe(profile, loc, material=None, segments=10, rot=(0, 0, 0), name="lathe",
          smooth=False):
    """Revolve a (radius, z) profile around Z. Points with radius 0 become poles."""
    verts, rings = [], []
    for r, z in profile:
        if r <= 1e-6:
            verts.append((0, 0, z))
            rings.append([len(verts) - 1])
        else:
            ring = []
            for i in range(segments):
                a = 2 * math.pi * i / segments
                verts.append((r * math.cos(a), r * math.sin(a), z))
                ring.append(len(verts) - 1)
            rings.append(ring)
    faces = []
    for a, b in zip(rings, rings[1:]):
        if len(a) == 1 and len(b) == 1:
            continue
        if len(a) == 1:
            for i in range(segments):
                faces.append([a[0], b[(i + 1) % segments], b[i]][::-1])
        elif len(b) == 1:
            for i in range(segments):
                faces.append([a[i], a[(i + 1) % segments], b[0]])
        else:
            for i in range(segments):
                j = (i + 1) % segments
                faces.append([a[i], a[j], b[j], b[i]])
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    me.update()
    o = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(o)
    o.location = loc
    o.rotation_euler = rot
    _finish(o, material, 0)
    if smooth:
        for poly in o.data.polygons:
            poly.use_smooth = True
    return o


# acorn proportions for a unit-height acorn (nut + cap), pointed end down
NUT_PROFILE = [(0.0, 0.0), (0.07, 0.035), (0.15, 0.12), (0.2, 0.24), (0.205, 0.34),
               (0.185, 0.42), (0.0, 0.44)]
CAP_PROFILE = [(0.0, 0.36), (0.19, 0.37), (0.235, 0.41), (0.25, 0.47), (0.225, 0.54),
               (0.15, 0.60), (0.0, 0.625)]


def acorn(loc, P, kind="solid", size=1.0, rot_z=0.0, carrier=True, cap=True, seal=False):
    """The product's signature: an acorn-shaped capsule, pointed end down.

    kind: 'wire'  - cyan holographic spec
          'solid' - molten amber nut under a dark faceted graphite cap
          'bare'  - nut only (cap not fitted yet)
    """
    x, y, z = loc
    s = size
    base = z
    if carrier:
        cyl(0.27 * s, 0.07 * s, (x, y, z + 0.035 * s), P["graphite"], verts=16, bevel=0.01,
            name="carrier")
        cyl(0.13 * s, 0.03 * s, (x, y, z + 0.08 * s), P["steel"], verts=16, bevel=0,
            name="carrier_cup")
        base = z + 0.05 * s
    rot = (0, 0, rot_z)
    if kind == "wire":
        nut = lathe([(r * s, h * s) for r, h in NUT_PROFILE], (x, y, base), P["cyan"],
                    segments=8, rot=rot, name="spec_nut")
        w = nut.modifiers.new("wire", "WIREFRAME")
        w.thickness = 0.018 * s
        if cap:
            c = lathe([(r * s, h * s) for r, h in CAP_PROFILE], (x, y, base), P["cyan"],
                      segments=8, rot=rot, name="spec_cap")
            w = c.modifiers.new("wire", "WIREFRAME")
            w.thickness = 0.018 * s
        lathe([(r * s * 0.7, h * s * 0.85 + 0.03 * s) for r, h in NUT_PROFILE], (x, y, base),
              P["cyan_faint"], segments=8, rot=rot, name="spec_core")
        return nut
    lathe([(r * s, h * s) for r, h in NUT_PROFILE], (x, y, base), P["nut"], segments=12,
          rot=rot, name="nut")
    if kind == "bare" or not cap:
        return None
    lathe([(r * s, h * s) for r, h in CAP_PROFILE], (x, y, base), P["graphite_lt"],
          segments=9, rot=rot, name="cap")
    cyl(0.03 * s, 0.12 * s, (x, y, base + 0.66 * s), P["graphite_lt"], verts=6, bevel=0,
        name="stem")
    if seal:
        torus(0.255 * s, 0.018 * s, (x, y, base + 0.47 * s), P["amber"], name="seal")
    return None


def empty(loc, rot=(0, 0, 0), name="pivot", parent=None):
    o = bpy.data.objects.new(name, None)
    o.location = loc
    o.rotation_euler = rot
    bpy.context.collection.objects.link(o)
    if parent is not None:
        o.parent = parent
    return o


def set_parent(child, parent):
    """Parent while keeping the child's current world transform."""
    bpy.context.view_layer.update()
    mw = child.matrix_world.copy()
    child.parent = parent
    child.matrix_parent_inverse = parent.matrix_world.inverted()
    child.matrix_world = mw


def local_box(size, loc, material, parent, bevel=0.03, name="part"):
    """Box defined in the parent's local space."""
    bpy.ops.mesh.primitive_cube_add(size=1, location=(0, 0, 0))
    o = bpy.context.active_object
    o.name = name
    o.scale = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    _finish(o, material, bevel)
    o.parent = parent
    o.location = loc
    return o


def local_cyl(radius, depth, loc, material, parent, rot=(0, 0, 0), verts=24,
              bevel=0.02, name="part"):
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=radius, depth=depth)
    o = bpy.context.active_object
    o.name = name
    _finish(o, material, bevel)
    o.parent = parent
    o.location = loc
    o.rotation_euler = rot
    return o


# ---------------------------------------------------------------- lights

def point(loc, color, power, radius=0.3, name="pt"):
    d = bpy.data.lights.new(name, "POINT")
    d.color = color[:3]
    d.energy = power
    d.shadow_soft_size = radius
    o = bpy.data.objects.new(name, d)
    o.location = loc
    bpy.context.collection.objects.link(o)
    return o


def area(loc, rot, color, power, size=2.0, name="area"):
    d = bpy.data.lights.new(name, "AREA")
    d.color = color[:3]
    d.energy = power
    d.size = size
    o = bpy.data.objects.new(name, d)
    o.location = loc
    o.rotation_euler = rot
    bpy.context.collection.objects.link(o)
    return o


def sun(rot, color, strength, angle=0.1, name="sun"):
    d = bpy.data.lights.new(name, "SUN")
    d.color = color[:3]
    d.energy = strength
    d.angle = angle
    o = bpy.data.objects.new(name, d)
    o.rotation_euler = rot
    bpy.context.collection.objects.link(o)
    return o


# ---------------------------------------------------------------- camera

def iso_camera(target, azimuth_deg, elevation_deg, ortho_scale, shift=(0, 0), dist=60):
    az, el = math.radians(azimuth_deg), math.radians(elevation_deg)
    t = Vector(target)
    offset = Vector((math.cos(el) * math.cos(az), math.cos(el) * math.sin(az), math.sin(el)))
    cam_data = bpy.data.cameras.new("cam")
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = ortho_scale
    cam_data.shift_x, cam_data.shift_y = shift
    cam_data.clip_end = 400
    cam = bpy.data.objects.new("cam", cam_data)
    cam.location = t + offset * dist
    direction = t - cam.location
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    bpy.context.collection.objects.link(cam)
    bpy.context.scene.camera = cam
    return cam


# ---------------------------------------------------------------- factory parts

BELT_Z = 0.9      # top surface of belts
BELT_W = 1.1      # belt width


def conveyor(x0, x1, y, P, width=BELT_W, edge="amber_soft", legs=True, rollers=True,
             fade_from=None):
    """Straight conveyor from x0 to x1 at lateral position y."""
    L = x1 - x0
    cx = (x0 + x1) / 2
    # belt surface
    box((L, width, 0.10), (cx, y, BELT_Z - 0.05), P["rubber"], bevel=0.02, name="belt")
    # side frames
    for s in (-1, 1):
        yy = y + s * (width / 2 + 0.07)
        box((L + 0.1, 0.14, 0.30), (cx, yy, BELT_Z - 0.10), P["graphite"], bevel=0.03,
            name="rail")
        # glowing strip on outer face
        if edge:
            box((L + 0.06, 0.02, 0.035), (cx, yy + s * 0.075, BELT_Z - 0.06), P[edge],
                bevel=0, name="strip")
    # rollers peeking out at the ends
    if rollers:
        for xx in (x0, x1):
            cyl(0.13, width + 0.06, (xx, y, BELT_Z - 0.12), P["steel"],
                rot=(math.pi / 2, 0, 0), verts=20, name="roller")
    # under-frame and legs
    box((L - 0.2, width * 0.7, 0.16), (cx, y, BELT_Z - 0.38), P["blueblack"], bevel=0.02,
        name="underframe")
    if legs:
        n = max(2, int(L / 2.2) + 1)
        for i in range(n):
            xx = x0 + 0.3 + i * (L - 0.6) / (n - 1)
            for s in (-1, 1):
                box((0.12, 0.12, BELT_Z - 0.25), (xx, y + s * width * 0.38, (BELT_Z - 0.25) / 2),
                    P["graphite"], bevel=0.02, name="leg")
            box((0.14, width * 0.9, 0.08), (xx, y, 0.18), P["graphite"], bevel=0.01,
                name="brace")


def feature_block(loc, P, kind="solid", size=0.46, rot_z=0.0):
    """A 'feature' travelling on the belt."""
    x, y, z = loc
    zc = z + size / 2
    if kind == "wire":
        # spec stage: holographic cyan wireframe
        o = box((size, size, size), (x, y, zc), P["cyan"], bevel=0, rot=(0, 0, rot_z),
                name="spec")
        w = o.modifiers.new("wire", "WIREFRAME")
        w.thickness = 0.03
        box((size * 0.6, size * 0.6, size * 0.6), (x, y, zc), P["cyan_faint"], bevel=0.01,
            rot=(0, 0, rot_z), name="spec_core")
        return o
    if kind == "solid":
        # built: dark shell with a molten core showing through the seams
        box((size * 0.92, size * 0.92, size * 0.92), (x, y, zc), P["amber"], bevel=0.02,
            rot=(0, 0, rot_z), name="core")
        for k in range(3):
            # three slabs with gaps -> glowing seams
            h = size * 0.29
            box((size, size, h), (x, y, z + 0.02 + h / 2 + k * (h + size * 0.045)),
                P["graphite_lt"], bevel=0.025, rot=(0, 0, rot_z), name="shell")
        return None
    if kind == "glow":
        return box((size, size, size), (x, y, zc), P["amber"], bevel=0.04,
                   rot=(0, 0, rot_z), name="glowblock")
    if kind == "crate":
        box((size, size, size), (x, y, zc), P["graphite"], bevel=0.03, rot=(0, 0, rot_z),
            name="crate")
        box((size * 1.01, size * 1.01, 0.05), (x, y, z + size * 0.62), P["amber_soft"],
            bevel=0, rot=(0, 0, rot_z), name="crate_band")
        return None


def robot_arm(base_loc, P, yaw=0.0, shoulder=-0.55, elbow=1.35, wrist=0.75,
              grip_open=0.08, scale=1.0, holding=None):
    """Six-ish axis industrial arm. Angles in radians; returns tool-tip empty."""
    s = scale
    bx, by, bz = base_loc
    # pedestal
    cyl(0.55 * s, 0.25 * s, (bx, by, bz + 0.125 * s), P["graphite"], verts=8, bevel=0.03,
        name="arm_plinth")
    cyl(0.42 * s, 0.05 * s, (bx, by, bz + 0.27 * s), P["amber_soft"], verts=32, bevel=0,
        name="arm_ring")
    root = empty((bx, by, bz + 0.3 * s), (0, 0, yaw), name="arm_yaw")
    local_cyl(0.38 * s, 0.35 * s, (0, 0, 0.17 * s), P["graphite_lt"], root, verts=24,
              name="turntable")
    local_box((0.5 * s, 0.55 * s, 0.5 * s), (0, 0, 0.55 * s), P["graphite"], root,
              bevel=0.06 * s, name="shoulder_housing")
    sh = empty((0, 0, 0.62 * s), (0, shoulder, 0), name="arm_shoulder", parent=root)
    local_cyl(0.24 * s, 0.7 * s, (0, 0, 0), P["steel"], sh, rot=(math.pi / 2, 0, 0),
              name="shoulder_joint")
    local_cyl(0.25 * s, 0.03 * s, (0, 0.36 * s, 0), P["amber"], sh, rot=(math.pi / 2, 0, 0),
              name="shoulder_light")
    local_box((0.36 * s, 0.38 * s, 1.75 * s), (0, 0, 0.88 * s), P["graphite_lt"], sh,
              bevel=0.08 * s, name="upper_arm")
    local_box((0.04 * s, 0.06 * s, 1.2 * s), (0.18 * s, 0, 0.88 * s), P["cyan_soft"], sh,
              bevel=0, name="upper_arm_line")
    el = empty((0, 0, 1.75 * s), (0, elbow, 0), name="arm_elbow", parent=sh)
    local_cyl(0.2 * s, 0.56 * s, (0, 0, 0), P["steel"], el, rot=(math.pi / 2, 0, 0),
              name="elbow_joint")
    local_box((0.28 * s, 0.30 * s, 1.45 * s), (0, 0, 0.72 * s), P["graphite_lt"], el,
              bevel=0.07 * s, name="forearm")
    wr = empty((0, 0, 1.45 * s), (0, wrist, 0), name="arm_wrist", parent=el)
    local_cyl(0.15 * s, 0.38 * s, (0, 0, 0), P["steel"], wr, rot=(math.pi / 2, 0, 0),
              name="wrist_joint")
    local_cyl(0.13 * s, 0.32 * s, (0, 0, 0.2 * s), P["graphite"], wr, name="flange")
    local_cyl(0.135 * s, 0.035 * s, (0, 0, 0.33 * s), P["amber"], wr, bevel=0,
              name="flange_light")
    local_box((0.36 * s, 0.14 * s, 0.1 * s), (0, 0, 0.4 * s), P["graphite_lt"], wr,
              bevel=0.02 * s, name="gripper_palm")
    for side in (-1, 1):
        local_box((0.05 * s, 0.12 * s, 0.3 * s),
                  (side * (grip_open + 0.03) * s, 0, 0.58 * s), P["steel"], wr,
                  bevel=0.015 * s, name="finger")
    tip = empty((0, 0, 0.62 * s), name="arm_tip", parent=wr)
    if holding is not None:
        holding.parent = wr
        holding.location = (0, 0, 0.62 * s)
    return tip
