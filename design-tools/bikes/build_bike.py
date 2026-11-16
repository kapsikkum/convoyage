"""
Builds one Convoyage motorcycle in Blender from a JSON definition, in the same style and coordinate frame as the cars:
a low-poly, smooth-shaded model (lofted bodywork, extruded panels, lathed wheels) exported as GLB with the same material
names so the app shades it like every other vehicle. Wheels ARE included (the app does not place wheels on a bike).

  blender -b --factory-startup --python build_bike.py -- yamaha-mt-07.json out.glb preview.png

Coordinates: metres, +X forward (nose at +L/2), +Y left, +Z up, ground at Z=0. The glTF exporter turns this into the
app's frame (x forward, y up, z right). Materials: paint, glass, dark, trim, chrome, head, tail, fog, plate, black,
accent (a fixed livery colour that is not the user's paint). The right-hand side of the bike is -Y.

The JSON gives the published numbers (length, width, height, wheelbase, tyres) and the style ("naked" or "sport");
the shapes themselves are drawn here per style, in metres from those numbers.
"""
import bpy, bmesh, json, math, sys
from mathutils import Vector

argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
spec = json.load(open(argv[0], encoding='utf-8'))
out_glb = argv[1]
preview = argv[2] if len(argv) > 2 else None

L, W, H, WB = (v / 1000.0 for v in spec['mm'])
TYRE_F, TYRE_R = spec.get('tyreF', 0.600), spec.get('tyreR', 0.630)
TW_F, TW_R = spec.get('tyreWF', 0.120), spec.get('tyreWR', 0.180)
RF, RR = TYRE_F / 2, TYRE_R / 2
FX = L / 2 - RF - spec.get('noseOverhang', 0.0)   # front axle: the front tyre is the nose
RX = FX - WB
STYLE = spec.get('style', 'naked')
RAKE = math.radians(spec.get('rake', 25.0))

COLORS = {
    'paint': spec.get('previewPaint', (0.75, 0.05, 0.06, 1)), 'glass': (0.05, 0.08, 0.12, 1),
    'dark': (0.03, 0.03, 0.035, 1), 'trim': (0.16, 0.17, 0.18, 1), 'chrome': (0.72, 0.74, 0.78, 1),
    'head': (0.92, 0.97, 1, 1), 'tail': (0.9, 0.05, 0.1, 1), 'fog': (1, 0.55, 0.1, 1), 'plate': (0.92, 0.92, 0.9, 1),
    'black': (0.008, 0.008, 0.009, 1), 'rim': (0.55, 0.57, 0.60, 1), 'accent': (0.42, 0.75, 0.18, 1),
}
MATS, MAT_INDEX = {}, {}
for i, (name, col) in enumerate(COLORS.items()):
    col = tuple(col)
    m = bpy.data.materials.new(name)
    m.diffuse_color = col
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
    bsdf.inputs['Base Color'].default_value = col
    bsdf.inputs['Roughness'].default_value = 0.3 if name in ('paint', 'chrome', 'glass', 'accent') else 0.65
    if name in ('head', 'tail', 'fog'):
        bsdf.inputs['Emission Color'].default_value = col
        bsdf.inputs['Emission Strength'].default_value = 1.0
    MATS[name], MAT_INDEX[name] = m, i

for o in list(bpy.data.objects):
    bpy.data.objects.remove(o)

parts = []
SHARP = math.radians(38)


# ---- primitives ------------------------------------------------------------------------------------------------

def mesh(name, verts, faces, mat, sharp=SHARP, flat=True):
    bm = bmesh.new()
    vs = [bm.verts.new(v) for v in verts]
    for f in faces:
        try:
            bm.faces.new([vs[i] for i in f])
        except ValueError:
            pass
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-5)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    for f in bm.faces:
        f.smooth = not flat
        f.material_index = MAT_INDEX[mat]
    for e in bm.edges:
        if len(e.link_faces) != 2 or e.calc_face_angle(0) > sharp:
            e.smooth = False
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for n in COLORS:
        me.materials.append(MATS[n])
    ob = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(ob)
    parts.append(ob)
    return ob


def ring_faces(rings, cap0=True, cap1=True, closed=True):
    """Quads between consecutive rings of equal length, plus end caps."""
    n = len(rings[0])
    faces = []
    for i in range(len(rings) - 1):
        a, b = rings[i], rings[i + 1]
        for k in range(n if closed else n - 1):
            k2 = (k + 1) % n
            faces.append((a[k], a[k2], b[k2], b[k]))
    if cap0:
        faces.append(tuple(reversed(rings[0])))
    if cap1:
        faces.append(tuple(rings[-1]))
    return faces


def build_rings(ring_pts, mat, name='loft', cap0=True, cap1=True, sharp=SHARP, closed=True):
    verts, rings = [], []
    for pts in ring_pts:
        rings.append(list(range(len(verts), len(verts) + len(pts))))
        verts += pts
    return mesh(name, verts, ring_faces(rings, cap0, cap1, closed), mat, sharp)


def sp(v, e):
    return math.copysign(abs(v) ** e, v)


def S(x, zb, zt, hw, p=2.4, yc=0.0, top=1.0, bot=1.0, n=None):
    """A loft section at x: bottom, top, half width, superellipse power, centre y, width factors at top and bottom."""
    return dict(x=x, zb=zb, zt=zt, hw=hw, p=p, yc=yc, top=top, bot=bot)


def loft(sections, mat, n=8, name='loft', cap0=True, cap1=True, sharp=SHARP):
    rings = []
    for s in sections:
        zm, hz = (s['zb'] + s['zt']) / 2, (s['zt'] - s['zb']) / 2
        e = 2.0 / s['p']
        pts = []
        for k in range(n):
            a = 2 * math.pi * k / n
            c, si = math.cos(a), math.sin(a)
            wf = s['top'] if si > 0 else s['bot']
            wf = 1 + (wf - 1) * abs(si)
            pts.append((s['x'], s['yc'] + s['hw'] * wf * sp(c, e), zm + hz * sp(si, e)))
        rings.append(pts)
    return build_rings(rings, mat, name, cap0, cap1, sharp)


def frame_at(t, prev_n):
    n = prev_n - t * prev_n.dot(t)
    if n.length < 1e-4:
        n = Vector((0, 0, 1)) if abs(t.z) < 0.9 else Vector((1, 0, 0))
        n = n - t * n.dot(t)
    n.normalize()
    return n, t.cross(n)


def pipe(points, r, mat, n=6, name='pipe', caps=True, sharp=SHARP, rs=None):
    """A tube along a polyline; [rs] gives a radius per point."""
    P = [Vector(p) for p in points]
    rings = []
    nrm = Vector((0, 0, 1))
    for i, p in enumerate(P):
        t = (P[min(i + 1, len(P) - 1)] - P[max(i - 1, 0)]).normalized()
        nrm, bi = frame_at(t, nrm)
        rr = rs[i] if rs else r
        rings.append([tuple(p + (nrm * math.cos(2 * math.pi * k / n) + bi * math.sin(2 * math.pi * k / n)) * rr) for k in range(n)])
    return build_rings(rings, mat, name, caps, caps, sharp)


def tube(p0, p1, r, mat, n=6, r1=None):
    return pipe([p0, p1], r, mat, n, rs=[r, r1 if r1 is not None else r])


def obox(center, size, mat, rot=(0, 0, 0), bevel=0.0, seg=1):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center, rotation=rot)
    ob = bpy.context.active_object
    ob.scale = size
    bpy.ops.object.transform_apply(scale=True, rotation=True, location=True)
    if bevel > 0:
        mod = ob.modifiers.new('bev', 'BEVEL')
        mod.width = bevel
        mod.segments = seg
        bpy.context.view_layer.objects.active = ob
        bpy.ops.object.modifier_apply(modifier=mod.name)
    return adopt(ob, mat)


def adopt(ob, mat, sharp=SHARP):
    me = ob.data
    verts = [tuple(v.co) for v in me.vertices]
    faces = [tuple(p.vertices) for p in me.polygons]
    bpy.data.objects.remove(ob)
    return mesh('p', verts, faces, mat, sharp)


def blob(center, radii, mat, seg=6, rings=4, rot=(0, 0, 0)):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=rings, radius=1, location=center, rotation=rot)
    ob = bpy.context.active_object
    ob.scale = radii
    bpy.ops.object.transform_apply(scale=True, rotation=True, location=True)
    return adopt(ob, mat, math.radians(80))


def lathe(cx, cz, profile, mat, seg=10, y0=0.0, name='lathe', sharp=SHARP, closed=True):
    """Revolves a closed (r, y) profile about the axle (along Y) at (cx, y0, cz)."""
    rings = []
    for (r, y) in profile:
        rings.append([(cx + r * math.cos(2 * math.pi * k / seg), y0 + y, cz + r * math.sin(2 * math.pi * k / seg)) for k in range(seg)])
    if closed:
        rings.append(rings[0])
        verts, idx = [], []
        for pts in rings[:-1]:
            idx.append(list(range(len(verts), len(verts) + seg)))
            verts += pts
        idx.append(idx[0])
        faces = ring_faces(idx, False, False)
        return mesh(name, verts, faces, mat, sharp)
    return build_rings(rings, mat, name, True, True, sharp)


def disc(cx, cz, y, r0, r1, t, mat, seg=10):
    """A flat ring in the wheel plane (brake rotor, rim face): inner radius r0, outer r1, thickness t, at y."""
    return lathe(cx, cz, [(r0, -t / 2), (r1, -t / 2), (r1, t / 2), (r0, t / 2)], mat, seg, y0=y, sharp=math.radians(60))


def offset_poly(pts, d):
    """Moves every vertex of a counter-clockwise (x, z) polygon inwards by d (mitred)."""
    n = len(pts)
    out = []
    for i in range(n):
        p0, p1, p2 = Vector(pts[i - 1]), Vector(pts[i]), Vector(pts[(i + 1) % n])
        e1 = (p1 - p0).normalized(); e2 = (p2 - p1).normalized()
        n1 = Vector((-e1.y, e1.x)); n2 = Vector((-e2.y, e2.x))
        m = n1 + n2
        k = 1 + n1.dot(n2)
        off = m * (d / k) if k > 0.3 else m.normalized() * d
        out.append(tuple(p1 + off))
    return out


def area(pts):
    return sum(pts[i - 1][0] * pts[i][1] - pts[i][0] * pts[i - 1][1] for i in range(len(pts))) / 2


def slab(pts, y_in, y_out, mat, inset=0.012, name='slab', sharp=SHARP):
    """A side panel: an (x, z) outline extruded across from y_in to y_out with a chamfered outer face.
    y_in / y_out may be functions of (x, z) so a panel can follow a curved body."""
    if area(pts) < 0:
        pts = list(reversed(pts))
    fi = y_in if callable(y_in) else (lambda x, z, v=y_in: v)
    fo = y_out if callable(y_out) else (lambda x, z, v=y_out: v)
    sgn = 1 if fo(*pts[0]) >= fi(*pts[0]) else -1
    inner = [(x, fi(x, z), z) for x, z in pts]
    mid = [(x, fo(x, z) - sgn * inset * 0.8, z) for x, z in pts]
    ins = offset_poly(pts, inset)
    outer = [(x, fo(x, z), z) for x, z in ins]
    return build_rings([inner, mid, outer], mat, name, True, True, sharp)


def both(fn):
    """Calls fn(s) for the left (s=1) and right (s=-1) side."""
    for s in (1, -1):
        fn(s)


def arc_band(cx, cz, r, a0, a1, hw, t, mat, n=6, dome=0.012, yc=0.0):
    """A fender: a band around the axle at radius r from angle a0 to a1 (degrees, 0 = forward, 90 = up)."""
    prof = []
    m = 6
    for k in range(m + 1):
        u = -1 + 2 * k / m
        prof.append((t + dome * (1 - u * u), u * hw))
    for k in range(m, -1, -1):
        u = -1 + 2 * k / m
        prof.append((0.0, u * hw * 0.96))
    rings = []
    for i in range(n + 1):
        a = math.radians(a0 + (a1 - a0) * i / n)
        d = Vector((math.cos(a), 0, math.sin(a)))
        rings.append([tuple(Vector((cx, yc, cz)) + d * (r + dr) + Vector((0, y, 0))) for dr, y in prof])
    return build_rings(rings, mat, 'fender', True, True)


def beam(p0, p1, w0, w1, t, mat, y=0.0):
    """A flat spoke in the wheel plane from p0 to p1 ((x, z) points), widths w0 -> w1, thickness t across."""
    a, b = Vector((p0[0], p0[1])), Vector((p1[0], p1[1]))
    d = (b - a).normalized()
    nn = Vector((-d.y, d.x))
    q = [a + nn * w0 / 2, b + nn * w1 / 2, b - nn * w1 / 2, a - nn * w0 / 2]
    verts = [(v.x, y - t / 2, v.y) for v in q] + [(v.x, y + t / 2, v.y) for v in q]
    faces = [(0, 1, 2, 3), (7, 6, 5, 4), (0, 4, 5, 1), (1, 5, 6, 2), (2, 6, 7, 3), (3, 7, 4, 0)]
    return mesh('spoke', verts, faces, mat, math.radians(50))


def project(target, outline, mat, off=0.006, depth=0.02, sub=3, name='feature', side=0):
    """A flat feature pushed onto [target]: side 0 draws it in front view ((y, z) outline, projected along -X);
    side 1 / -1 draws it in side view ((x, z) outline) onto the left / right flank."""
    from mathutils.bvhtree import BVHTree
    tree = BVHTree.FromObject(target, bpy.context.evaluated_depsgraph_get())
    pts = []
    for i in range(len(outline)):
        a, b = Vector(outline[i]), Vector(outline[(i + 1) % len(outline)])
        for k in range(sub):
            pts.append(a.lerp(b, k / sub))
    c = sum(pts, Vector((0, 0))) / len(pts)

    def hit(p):
        if side == 0:
            o, d = Vector((3.0, p.x, p.y)), Vector((-1, 0, 0))
        else:
            o, d = Vector((p.x, 3.0 * side, p.y)), Vector((0, -side, 0))
        return tree.ray_cast(o, d)[:2]

    # The face follows the body: rings of the outline shrunk towards its centre, each projected, then a fan.
    verts, rings, back = [], [], []
    for p in pts:
        loc, nrm = hit(p)
        if loc is None:
            return None
        back.append(len(verts)); verts.append(tuple(loc - nrm * depth))
    for sc in (1.0, 0.66, 0.33):
        ring = []
        for p in pts:
            loc, nrm = hit(c + (p - c) * sc)
            if loc is None:
                return None
            ring.append(len(verts)); verts.append(tuple(loc + nrm * off))
        rings.append(ring)
    loc, nrm = hit(c)
    if loc is None:
        return None
    mid = len(verts); verts.append(tuple(loc + nrm * off))
    faces = ring_faces([back] + rings, cap0=True, cap1=False)
    n = len(pts)
    faces += [(rings[-1][k], rings[-1][(k + 1) % n], mid) for k in range(n)]
    return mesh(name, verts, faces, mat, sharp=math.radians(30))


def fork_axis(length):
    """A point up the steering axis from the front axle."""
    return (FX - math.sin(RAKE) * length, 0, RF + math.cos(RAKE) * length)


# ---- wheels ----------------------------------------------------------------------------------------------------

def wheel(x, r, tw, front, pairs, rim_mat='rim'):
    z = r
    rim = 0.2159                                       # 17 inch
    hw = tw / 2
    # Tyre: a round-shouldered motorcycle section from bead to crown.
    prof = []
    for k in range(9):
        a = math.pi / 2 - math.pi * k / 8              # +90 (left bead) .. -90 (right bead)
        rr = r - hw * 0.62 * (1 - math.cos(a)) ** 1.15
        prof.append((max(rr, rim + 0.012), hw * 0.98 * math.sin(a)))
    prof.append((rim + 0.004, -hw * 0.80))
    prof = [(rim + 0.004, hw * 0.80)] + prof
    lathe(x, z, list(reversed(prof)), 'dark', seg=10, name='tyre', sharp=math.radians(70))
    # Rim barrel and lips.
    rw = tw * 0.62
    lathe(x, z, [(rim - 0.018, -rw / 2), (rim + 0.006, -rw / 2), (rim + 0.006, -rw / 2 + 0.008), (rim - 0.010, -rw / 2 + 0.010),
                 (rim - 0.010, rw / 2 - 0.010), (rim + 0.006, rw / 2 - 0.008), (rim + 0.006, rw / 2), (rim - 0.018, rw / 2)],
          rim_mat, seg=10, name='rim', sharp=math.radians(50))
    # Hub.
    hub_w = tw * (0.9 if front else 0.75)
    lathe(x, z, [(0.012, -hub_w / 2), (0.045, -hub_w / 2), (0.05, -0.02), (0.05, 0.02), (0.045, hub_w / 2), (0.012, hub_w / 2)],
          rim_mat, seg=6, name='hub', closed=False)
    # Spokes in pairs (each pair a slim V meeting at the rim, like the real wheels).
    for i in range(pairs):
        base = 2 * math.pi * i / pairs + (math.pi / 2 if front else 0.3)
        for side in (-1, 1):
            a_hub = base + side * 0.10
            a_rim = base + side * (0.30 if pairs <= 3 else 0.17)
            p0 = (x + 0.045 * math.cos(a_hub), z + 0.045 * math.sin(a_hub))
            p1 = (x + (rim - 0.012) * math.cos(a_rim), z + (rim - 0.012) * math.sin(a_rim))
            beam(p0, p1, 0.030, 0.020, 0.022, rim_mat)
    # Brakes: twin front discs, one rear disc on the right; sprocket on the left at the back.
    if front:
        for s in (1, -1):
            disc(x, z, s * (hw * 0.5 + 0.035), 0.105, 0.150, 0.006, 'chrome', seg=10)
            disc(x, z, s * (hw * 0.5 + 0.033), 0.05, 0.106, 0.010, 'trim', seg=6)
    else:
        disc(x, z, -(hw * 0.55 + 0.012), 0.07, 0.122, 0.005, 'chrome', seg=10)
        disc(x, z, hw * 0.55 + 0.02, 0.05, 0.105, 0.008, 'trim', seg=8)


wheel(FX, RF, TW_F, True, spec.get('spokePairs', 5))
wheel(RX, RR, TW_R, False, spec.get('spokePairs', 5))

# ---- common running gear: fork, swingarm, chain, pegs ----------------------------------------------------------
TOP = spec.get('topClamp', 0.70)                  # axle to top clamp along the steering axis
fork_y = TW_F / 2 + 0.05
inverted = spec.get('invertedFork', False)
for s in (1, -1):
    ax = Vector((FX, s * fork_y, RF))
    up = Vector((-math.sin(RAKE), 0, math.cos(RAKE)))
    if inverted:
        tube(ax - up * 0.02, ax + up * 0.30, 0.021, 'chrome')
        tube(ax + up * 0.28, ax + up * (TOP + 0.02), 0.029, 'black')
        obox(tuple(ax + up * 0.03 - Vector((0, 0, 0))), (0.06, 0.05, 0.08), 'black', rot=(0, -RAKE, 0), bevel=0.01)
    else:
        tube(ax - up * 0.03, ax + up * 0.36, 0.027, 'black')
        tube(ax + up * 0.34, ax + up * (TOP + 0.03), 0.019, 'chrome')
    # caliper behind the fork leg, gripping the disc
    cal = ax + Vector((-0.115, 0, 0.07))
    obox((cal.x, s * (TW_F / 2 + 0.035), cal.z), (0.05, 0.045, 0.11), 'trim', rot=(0, math.radians(-40), 0), bevel=0.012)
bot = Vector(fork_axis(TOP - 0.15)); top = Vector(fork_axis(TOP))
for c, h in ((bot, 0.035), (top, 0.03)):
    obox(tuple(c + Vector((0.005, 0, 0))), (0.075, 2 * fork_y + 0.06, h), 'trim', rot=(0, -RAKE, 0), bevel=0.012)
HEAD = Vector(fork_axis(TOP - 0.08)) - Vector((0.03, 0, 0))   # steering head (frame side)
tube(tuple(Vector(fork_axis(TOP - 0.20)) - Vector((0.035, 0, 0))), tuple(Vector(fork_axis(TOP)) - Vector((0.035, 0, 0))), 0.035, 'trim')

PIVOT = Vector((spec.get('pivotX', -0.17), 0, spec.get('pivotZ', 0.44)))
sa_y = TW_R / 2 + 0.035
for s in (1, -1):
    # Tapering swingarm beam, deeper at the pivot.
    a = Vector((PIVOT.x, s * sa_y, PIVOT.z)); b = Vector((RX, s * sa_y, RR))
    d = (b - a); ln = d.length; d.normalize()
    nz = Vector((-d.z, 0, d.x))
    pts = [a + nz * 0.055, b + nz * 0.028, b - nz * 0.028, a - nz * 0.06]
    slab([(p.x, p.z) for p in pts], s * (sa_y - 0.022), s * (sa_y + 0.022), spec.get('swingarm', 'trim'), inset=0.008)
    obox((RX, s * (sa_y + 0.012), RR), (0.05, 0.03, 0.05), 'chrome', bevel=0.006)       # axle block
obox((PIVOT.x + 0.04, 0, PIVOT.z), (0.08, 2 * sa_y + 0.02, 0.09), spec.get('swingarm', 'trim'), bevel=0.02)
# Chain (left side): two runs from the front sprocket to the rear one.
cy = TW_R / 2 + 0.06
fs = Vector((PIVOT.x + 0.10, cy, PIVOT.z + 0.0))
tube((fs.x, cy, fs.z + 0.04), (RX, cy, RR + 0.105), 0.007, 'dark', n=6)
tube((fs.x, cy, fs.z - 0.04), (RX, cy, RR - 0.105), 0.007, 'dark', n=6)
obox((fs.x + 0.02, cy - 0.01, fs.z), (0.12, 0.012, 0.12), 'dark', bevel=0.03)          # sprocket cover

# ---- the style: naked (MT-07) ---------------------------------------------------------------------------------

def naked():
    hx, hz = HEAD.x, HEAD.z
    # Steel tube frame (the MT-07's backbone): from the steering head over the engine and down to the pivot.
    for s in (1, -1):
        y0, y1 = s * 0.06, s * 0.115
        pipe([(hx, y0, hz + 0.03), (hx - 0.16, s * 0.10, hz - 0.03), (0.05, y1, 0.74), (-0.10, y1, 0.66), (PIVOT.x + 0.02, y1, PIVOT.z + 0.10)], 0.022, 'trim', n=8)
        pipe([(hx - 0.02, y0, hz - 0.06), (hx - 0.12, s * 0.10, 0.62), (0.18, y1, 0.50)], 0.019, 'trim', n=8)
        # Pivot plates (silver on the real bike) and the subframe up to the seat.
        slab([(PIVOT.x - 0.05, PIVOT.z - 0.08), (PIVOT.x + 0.07, PIVOT.z - 0.10), (PIVOT.x + 0.11, PIVOT.z + 0.05), (0.0, 0.70),
              (-0.07, 0.70), (PIVOT.x - 0.06, PIVOT.z + 0.08)], s * 0.10, s * 0.135, 'chrome', inset=0.01)
        pipe([(-0.06, s * 0.11, 0.68), (-0.40, s * 0.09, 0.76), (-0.72, s * 0.06, 0.80)], 0.014, 'trim', n=6)
        pipe([(PIVOT.x - 0.02, s * 0.12, PIVOT.z + 0.07), (-0.42, s * 0.10, 0.75)], 0.012, 'trim', n=6)
    # Parallel twin: crankcase, forward-tilted cylinders, head, side covers, sump.
    loft([S(-0.12, 0.30, 0.50, 0.13, 3), S(0.0, 0.24, 0.56, 0.15, 3), S(0.20, 0.22, 0.56, 0.155, 3), S(0.30, 0.26, 0.48, 0.13, 3)], 'dark', n=12)
    obox((0.24, 0, 0.62), (0.17, 0.27, 0.20), 'dark', rot=(0, math.radians(-22), 0), bevel=0.02)
    for i in range(4):
        obox((0.26 + i * 0.012, 0, 0.58 + i * 0.035), (0.18, 0.285, 0.008), 'trim', rot=(0, math.radians(-22), 0))
    obox((0.30, 0, 0.73), (0.15, 0.25, 0.08), 'black', rot=(0, math.radians(-22), 0), bevel=0.02)
    blob((0.06, -0.15, 0.38), (0.11, 0.03, 0.11), 'trim', seg=12)          # clutch cover, right
    blob((0.02, 0.15, 0.38), (0.09, 0.03, 0.09), 'trim', seg=12)           # generator cover, left
    # Radiator and its shrouds (the angular "air intake" side covers that read as the MT's face).
    obox((hx + 0.02, 0, 0.60), (0.04, 0.34, 0.27), 'trim', rot=(0, math.radians(-14), 0), bevel=0.008)
    obox((hx + 0.045, 0, 0.60), (0.01, 0.30, 0.23), 'black', rot=(0, math.radians(-14), 0))
    both(lambda s: slab([(hx + 0.07, 0.88), (hx - 0.06, 0.90), (0.20, 0.78), (0.22, 0.68), (hx - 0.02, 0.47), (hx + 0.05, 0.52)],
                        lambda x, z: s * 0.10, lambda x, z: s * (0.14 + 0.07 * max(0.0, z - 0.5)), 'black', inset=0.014, name='shroud'))
    both(lambda s: slab([(hx + 0.05, 0.90), (hx - 0.10, 0.93), (0.16, 0.82), (hx - 0.04, 0.80)],
                        lambda x, z: s * 0.15, lambda x, z: s * (0.168 + 0.07 * max(0.0, z - 0.5)), 'paint', inset=0.010, name='scoop'))
    # Fuel tank: high and short, falling to the seat.
    loft([S(hx + 0.04, 0.88, 0.96, 0.09, 3.0, top=0.8), S(hx - 0.02, 0.82, 1.02, 0.16, 3.4, top=0.75), S(0.26, 0.78, 1.035, 0.19, 3.4, top=0.72),
          S(0.12, 0.76, 1.00, 0.185, 3.4, top=0.72), S(0.02, 0.77, 0.935, 0.15, 3.0, top=0.75), S(-0.05, 0.78, 0.88, 0.12, 2.6, top=0.8)],
         'paint', n=16, name='tank')
    # Seat (rider then a stepped pillion) and the short tail.
    loft([S(-0.02, 0.76, 0.86, 0.14, 3.5, top=0.85), S(-0.10, 0.75, 0.83, 0.15, 3.5, top=0.85), S(-0.28, 0.76, 0.825, 0.15, 3.5, top=0.85),
          S(-0.40, 0.77, 0.84, 0.13, 3.5, top=0.85)], 'black', n=14, name='seat')
    loft([S(-0.40, 0.80, 0.89, 0.11, 3.2), S(-0.52, 0.81, 0.90, 0.105, 3.2), S(-0.66, 0.82, 0.895, 0.08, 3.0)], 'black', n=12, name='pillion')
    loft([S(-0.16, 0.66, 0.80, 0.12, 3), S(-0.42, 0.73, 0.84, 0.12, 3), S(-0.66, 0.79, 0.87, 0.085, 3), S(-0.86, 0.83, 0.89, 0.04, 2.6)],
         'paint', n=12, name='tail')
    obox((-0.865, 0, 0.865), (0.03, 0.07, 0.03), 'tail', rot=(0, math.radians(-20), 0), bevel=0.006)
    # Plate hanger, plate and indicators under the tail.
    slab([(-0.72, 0.80), (-0.80, 0.80), (-0.98, 0.60), (-0.96, 0.55), (-0.90, 0.58)], -0.035, 0.035, 'black', inset=0.006, name='hanger')
    obox((-0.985, 0, 0.53), (0.012, 0.20, 0.13), 'plate', rot=(0, math.radians(12), 0))
    obox((-0.975, 0, 0.53), (0.012, 0.21, 0.14), 'black', rot=(0, math.radians(12), 0))
    for s in (1, -1):
        tube((-0.93, s * 0.03, 0.63), (-0.94, s * 0.13, 0.64), 0.006, 'black', n=6)
        blob((-0.945, s * 0.14, 0.64), (0.025, 0.015, 0.012), 'fog', seg=8, rings=4)
    obox((-0.94, 0, 0.61), (0.015, 0.05, 0.025), 'tail')
    # Front: round headlight with a little cowl, indicators, clocks, wide bars and mirrors.
    lx, lz = hx + 0.17, hz - 0.11
    loft([S(lx - 0.10, lz - 0.065, lz + 0.065, 0.065, 2.2), S(lx - 0.03, lz - 0.095, lz + 0.09, 0.095, 2.2), S(lx + 0.005, lz - 0.09, lz + 0.085, 0.09, 2.2)],
         'black', n=16, name='lamp')
    loft([S(lx + 0.004, lz - 0.075, lz + 0.07, 0.075, 2.2), S(lx + 0.022, lz - 0.065, lz + 0.06, 0.065, 2.2)], 'head', n=16, name='lens')
    slab([(lx - 0.12, lz + 0.09), (lx - 0.02, lz + 0.11), (lx + 0.02, lz + 0.03), (lx - 0.05, lz + 0.05)], -0.10, 0.10, 'black', inset=0.01, name='cowl')
    for s in (1, -1):
        tube((lx - 0.06, s * 0.09, lz + 0.07), (lx - 0.04, s * 0.15, lz + 0.075), 0.007, 'black', n=6)
        blob((lx - 0.03, s * 0.16, lz + 0.075), (0.03, 0.016, 0.014), 'fog', seg=8, rings=4)
    obox((hx + 0.04, 0, hz + 0.12), (0.06, 0.12, 0.07), 'black', rot=(0, math.radians(-50), 0), bevel=0.01)   # clocks
    bz = hz + 0.14
    bx = hx - 0.04
    pipe([(bx + 0.02, -0.37, bz + 0.04), (bx + 0.01, -0.22, bz + 0.03), (bx + 0.03, -0.08, bz - 0.01), (bx + 0.03, 0.08, bz - 0.01),
          (bx + 0.01, 0.22, bz + 0.03), (bx + 0.02, 0.37, bz + 0.04)], 0.011, 'black', n=8)
    for s in (1, -1):
        tube((bx + 0.025, s * 0.06, bz - 0.01), (top.x, s * 0.06, top.z), 0.016, 'black', n=8)
        tube((bx + 0.021, s * 0.29, bz + 0.037), (bx + 0.022, s * 0.37, bz + 0.042), 0.017, 'black', n=8)   # grip
        tube((bx + 0.03, s * 0.24, bz + 0.035), (bx + 0.09, s * 0.33, bz + 0.03), 0.006, 'chrome', n=6)    # lever
        pipe([(bx + 0.0, s * 0.24, bz + 0.04), (bx - 0.02, s * 0.27, bz + 0.07), (bx - 0.025, s * 0.29, bz + 0.085)], 0.006, 'black', n=6)
        blob((bx - 0.025, s * 0.315, bz + 0.09), (0.022, 0.065, 0.032), 'black', seg=10, rings=6)
    # Short front fender and a rear hugger.
    arc_band(FX, RF, RF + 0.025, 35, 128, TW_F / 2 + 0.015, 0.008, 'paint', n=10)
    arc_band(RX, RR, RR + 0.02, 55, 120, TW_R / 2 + 0.01, 0.006, 'black', n=6)
    # Exhaust: two headers under the engine into the stubby can low on the right.
    for k, y in enumerate((0.05, -0.05)):
        pipe([(0.36, y, 0.56), (0.42, y, 0.44), (0.38 - 0.02 * k, y * 1.2, 0.26), (0.24, y * 0.6 - 0.02, 0.17), (0.06, -0.05, 0.17)], 0.021, 'chrome', n=8)
    loft([S(0.08, 0.13, 0.24, 0.06, 2.6, yc=-0.07), S(0.0, 0.12, 0.27, 0.085, 2.8, yc=-0.12), S(-0.14, 0.15, 0.30, 0.085, 2.8, yc=-0.15),
          S(-0.22, 0.19, 0.31, 0.07, 2.6, yc=-0.16)], 'trim', n=12, name='can')
    obox((-0.225, -0.16, 0.25), (0.012, 0.11, 0.08), 'chrome', bevel=0.01)
    blob((-0.232, -0.16, 0.25), (0.006, 0.03, 0.025), 'black', seg=8, rings=4)
    # Rear shock (laid down, right side) and pegs with silver heel plates.
    tube((PIVOT.x + 0.05, -0.06, PIVOT.z + 0.05), (-0.36, -0.07, 0.62), 0.03, 'trim')
    for s in (1, -1):
        tube((-0.10, s * 0.13, 0.36), (-0.10, s * 0.24, 0.36), 0.013, 'black', n=6)
        tube((-0.36, s * 0.12, 0.47), (-0.36, s * 0.22, 0.47), 0.011, 'black', n=6)
        slab([(-0.16, 0.33), (-0.04, 0.33), (-0.06, 0.47), (-0.18, 0.52)], s * 0.12, s * 0.135, 'chrome', inset=0.006, name='heel')
    obox((-0.10, 0.0, 0.33), (0.04, 0.06, 0.06), 'trim')   # side stand mount


# ---- the style: sport (ZX-6R 636) -----------------------------------------------------------------------------

def sport():
    hx, hz = HEAD.x, HEAD.z
    # Aluminium perimeter frame spar, mostly behind the fairing, showing above the pivot.
    for s in (1, -1):
        slab([(hx - 0.02, hz + 0.02), (hx - 0.10, hz + 0.04), (0.02, 0.78), (PIVOT.x + 0.04, PIVOT.z + 0.16), (PIVOT.x - 0.06, PIVOT.z + 0.04),
              (PIVOT.x - 0.02, PIVOT.z - 0.08), (PIVOT.x + 0.08, PIVOT.z - 0.06), (0.04, 0.64), (hx - 0.06, hz - 0.12)],
             s * 0.10, s * 0.15, 'trim', inset=0.012, name='spar')
        pipe([(-0.04, s * 0.11, 0.74), (-0.40, s * 0.09, 0.84), (-0.74, s * 0.05, 0.92)], 0.014, 'trim', n=6)
    # Engine, low behind the lower fairing.
    loft([S(-0.12, 0.28, 0.52, 0.13, 3), S(0.0, 0.24, 0.58, 0.15, 3), S(0.22, 0.24, 0.62, 0.15, 3), S(0.32, 0.30, 0.56, 0.12, 3)], 'dark', n=12)
    blob((0.04, -0.15, 0.40), (0.11, 0.03, 0.11), 'trim', seg=12)
    blob((0.02, 0.15, 0.40), (0.09, 0.03, 0.09), 'trim', seg=12)
    # Upper fairing: a pointed nose over the front wheel, widening to the mirrors.
    nx = FX + spec.get('noseX', 0.09)
    # One fairing from the pointed nose over the front wheel back past the engine: narrow at the tip, widest at the
    # mirrors, deep over the cylinders, closing in under the tank.
    secs = [S(nx, 0.735, 0.785, 0.035, 2.0), S(nx - 0.06, 0.665, 0.85, 0.12, 2.4, top=0.75),
            S(nx - 0.16, 0.59, 0.92, 0.19, 2.8, top=0.68), S(nx - 0.28, 0.53, 0.965, 0.232, 3.0, top=0.6),
            S(0.38, 0.49, 0.975, 0.245, 3.2, top=0.55), S(0.24, 0.48, 0.93, 0.245, 3.4, top=0.62),
            S(0.10, 0.52, 0.86, 0.22, 3.2, top=0.72), S(-0.02, 0.60, 0.82, 0.16, 2.8, top=0.8)]
    nose = loft(secs, 'paint', n=20, name='fairing')
    # Ram-air intake in the nose tip and the twin headlights either side of it, drawn in front view.
    project(nose, [(-0.03, 0.70), (0.03, 0.70), (0.035, 0.765), (-0.035, 0.765)], 'black', off=0.004, depth=0.03, name='ram')
    for s in (1, -1):
        bez = [(s * 0.035, 0.775), (s * 0.12, 0.815), (s * 0.19, 0.835), (s * 0.20, 0.78), (s * 0.15, 0.72), (s * 0.06, 0.735)]
        lamp = [(s * 0.05, 0.775), (s * 0.12, 0.805), (s * 0.18, 0.82), (s * 0.185, 0.785), (s * 0.145, 0.735), (s * 0.07, 0.748)]
        project(nose, bez, 'black', off=0.004, depth=0.03, name='bezel')
        project(nose, lamp, 'head', off=0.008, depth=0.03, name='lamp')
        # the 636's side vent and the black lower edge of the fairing
        project(nose, [(0.42, 0.66), (0.36, 0.74), (0.18, 0.68), (0.26, 0.60)], 'black', off=0.004, depth=0.03, side=s, name='vent')
    # Windscreen: a curved shell rising back from the nose to the clocks.
    sc = []
    for x, zt, hw in ((nx - 0.10, 0.905, 0.10), (nx - 0.18, 0.975, 0.15), (nx - 0.26, 1.035, 0.17), (nx - 0.33, 1.075, 0.16), (nx - 0.37, 1.09, 0.12)):
        ring = []
        for k in range(9):
            a = math.pi * k / 8
            ring.append((x, hw * math.cos(a), zt - 0.085 + 0.085 * math.sin(a) ** 0.7))
        ring += [(x - 0.008, p[1] * 0.97, p[2] - 0.01) for p in reversed(ring)]
        sc.append(ring)
    build_rings(sc, 'glass', 'screen', True, True)
    # Ebony lower fairing / belly pan with the lime KRT streak.
    lower = loft([S(0.46, 0.26, 0.52, 0.19, 2.6, bot=0.55), S(0.32, 0.19, 0.50, 0.225, 2.8, bot=0.6), S(0.12, 0.18, 0.50, 0.215, 2.8, bot=0.6),
                  S(-0.04, 0.20, 0.42, 0.15, 2.4, bot=0.7), S(-0.10, 0.24, 0.34, 0.09, 2.2)], 'black', n=16, name='lower')
    for s in (1, -1):
        project(lower, [(0.44, 0.46), (0.40, 0.42), (0.12, 0.36), (-0.06, 0.25), (-0.07, 0.29), (0.10, 0.41), (0.38, 0.475)],
                'accent', off=0.004, depth=0.03, side=s, name='streak')
    # Tank: tall at the front, sloping down under the rider.
    loft([S(nx - 0.36, 0.86, 0.96, 0.16, 3.4, top=0.7), S(0.36, 0.81, 0.995, 0.19, 3.6, top=0.66), S(0.22, 0.79, 1.005, 0.20, 3.6, top=0.64),
          S(0.10, 0.78, 0.975, 0.19, 3.4, top=0.66), S(0.02, 0.80, 0.91, 0.15, 2.8, top=0.75), S(-0.02, 0.81, 0.87, 0.12, 2.4)], 'paint', n=16, name='tank')
    # Clocks and clip-ons below the top clamp; mirrors with indicators on the fairing.
    obox((hx + 0.05, 0, hz + 0.08), (0.07, 0.16, 0.05), 'black', rot=(0, math.radians(-55), 0), bevel=0.012)
    for s in (1, -1):
        g0 = Vector((top.x + 0.0, s * 0.10, top.z - 0.03))
        tube(tuple(g0), (top.x - 0.06, s * 0.30, top.z - 0.07), 0.011, 'black', n=8)
        tube((top.x - 0.045, s * 0.25, top.z - 0.06), (top.x - 0.065, s * 0.33, top.z - 0.075), 0.017, 'black', n=8)
        tube((top.x - 0.03, s * 0.22, top.z - 0.05), (top.x + 0.04, s * 0.31, top.z - 0.07), 0.006, 'chrome', n=6)
        mx = nx - 0.30
        tube((mx, s * 0.21, 0.95), (mx - 0.02, s * 0.27, 1.01), 0.008, 'black', n=6)
        blob((mx - 0.03, s * 0.285, 1.02), (0.035, 0.065, 0.04), 'black', seg=10, rings=6)
        blob((mx + 0.002, s * 0.285, 1.015), (0.006, 0.04, 0.008), 'fog', seg=6, rings=4)
    # Seat and the sharp, high tail with its lime edge and LED tail light.
    loft([S(0.0, 0.79, 0.865, 0.15, 3.4, top=0.85), S(-0.12, 0.785, 0.845, 0.15, 3.4, top=0.85), S(-0.28, 0.80, 0.86, 0.12, 3.4, top=0.85),
          S(-0.33, 0.82, 0.88, 0.10, 3.0)], 'black', n=14, name='seat')
    tail = loft([S(-0.04, 0.66, 0.80, 0.14, 2.8), S(-0.30, 0.72, 0.84, 0.125, 2.8, top=0.85), S(-0.60, 0.86, 0.98, 0.08, 2.6, top=0.8),
          S(-0.84, 0.95, 1.02, 0.03, 2.2)], 'paint', n=14, name='tail')
    loft([S(-0.33, 0.89, 0.93, 0.085, 3.0), S(-0.52, 0.93, 0.975, 0.075, 3.0), S(-0.62, 0.955, 0.99, 0.05, 2.6)], 'black', n=10, name='pillion')
    for s in (1, -1):
        project(tail, [(-0.08, 0.745), (-0.62, 0.915), (-0.80, 0.975), (-0.80, 0.99), (-0.62, 0.94), (-0.10, 0.78)], 'accent', off=0.003, depth=0.02, side=s, name='tailstripe')
    obox((-0.83, 0, 0.98), (0.04, 0.07, 0.03), 'tail', rot=(0, math.radians(-30), 0), bevel=0.006)
    loft([S(-0.16, 0.66, 0.74, 0.10, 2.4), S(-0.50, 0.76, 0.84, 0.07, 2.4), S(-0.78, 0.87, 0.92, 0.03, 2.2)], 'black', n=10, name='undertail')
    # Plate hanger with indicators.
    slab([(-0.70, 0.84), (-0.78, 0.84), (-0.99, 0.62), (-0.97, 0.57), (-0.90, 0.60)], -0.035, 0.035, 'black', inset=0.006, name='hanger')
    obox((-0.995, 0, 0.55), (0.012, 0.20, 0.13), 'plate', rot=(0, math.radians(12), 0))
    obox((-0.985, 0, 0.55), (0.012, 0.21, 0.14), 'black', rot=(0, math.radians(12), 0))
    for s in (1, -1):
        tube((-0.93, s * 0.03, 0.66), (-0.94, s * 0.13, 0.67), 0.006, 'black', n=6)
        blob((-0.945, s * 0.14, 0.67), (0.025, 0.015, 0.012), 'fog', seg=8, rings=4)
    # Front fender (paint) and a rear hugger.
    arc_band(FX, RF, RF + 0.022, 32, 135, TW_F / 2 + 0.012, 0.008, 'paint', n=10)
    arc_band(RX, RR, RR + 0.02, 55, 125, TW_R / 2 + 0.01, 0.006, 'black', n=6)
    # Exhaust: headers under the engine to a link pipe and the triangular side silencer on the right.
    pipe([(0.20, -0.06, 0.20), (-0.02, -0.08, 0.18), (-0.14, -0.14, 0.30), (-0.22, -0.17, 0.38)], 0.026, 'trim', n=8)
    sil = []
    for (x, z, sz) in ((-0.20, 0.36, 0.06), (-0.28, 0.41, 0.09), (-0.50, 0.53, 0.095), (-0.58, 0.575, 0.08)):
        ring = []
        for k in range(3):
            a = math.pi / 2 + 2 * math.pi * k / 3
            for j in (-1, 1):
                aa = a + j * 0.30
                ring.append((x, -0.18 + sz * 0.62 * math.cos(aa), z + sz * math.sin(aa)))
        sil.append(ring)
    build_rings(sil, 'dark', 'silencer', True, False, sharp=math.radians(40))
    xe, ze = -0.585, 0.578
    loft([S(xe, ze - 0.06, ze + 0.06, 0.048, 2.2, yc=-0.18), S(xe - 0.03, ze - 0.05, ze + 0.05, 0.04, 2.2, yc=-0.18)], 'chrome', n=12, name='endcap')
    blob((xe - 0.031, -0.18, ze), (0.006, 0.025, 0.025), 'black', seg=8, rings=4)
    # Rear shock, pegs and hanger plates.
    tube((PIVOT.x + 0.02, 0, PIVOT.z + 0.04), (PIVOT.x - 0.02, 0, PIVOT.z + 0.28), 0.03, 'accent')
    for s in (1, -1):
        tube((-0.12, s * 0.13, 0.40), (-0.12, s * 0.23, 0.40), 0.013, 'black', n=6)
        tube((-0.36, s * 0.12, 0.52), (-0.36, s * 0.21, 0.52), 0.011, 'black', n=6)
        slab([(-0.18, 0.36), (-0.06, 0.38), (-0.08, 0.50), (-0.20, 0.56)], s * 0.12, s * 0.135, 'trim', inset=0.006, name='heel')


naked() if STYLE == 'naked' else sport()

# ---- join, centre on the length and export --------------------------------------------------------------------
for o in bpy.context.selected_objects:
    o.select_set(False)
for o in parts:
    o.select_set(True)
bpy.context.view_layer.objects.active = parts[0]
bpy.ops.object.join()
bike = bpy.context.active_object
bike.name = spec['id']
bm = bmesh.new()
bm.from_mesh(bike.data)
bmesh.ops.triangulate(bm, faces=bm.faces, quad_method='BEAUTY', ngon_method='EAR_CLIP')
bm.to_mesh(bike.data)
bm.free()
xs = [v.co.x for v in bike.data.vertices]
ys = [v.co.y for v in bike.data.vertices]
zs = [v.co.z for v in bike.data.vertices]
# PS1 budget: collapse the dense lofts down to the era's triangle counts. Faceting is the point, not a side effect.
target = int(spec.get('tris', 2400))
if len(bike.data.polygons) > target:
    mod = bike.modifiers.new('decimate', 'DECIMATE')
    mod.decimate_type = 'COLLAPSE'
    mod.ratio = max(0.05, target / len(bike.data.polygons))
    bpy.context.view_layer.objects.active = bike
    bpy.ops.object.modifier_apply(modifier=mod.name)
xs = [v.co.x for v in bike.data.vertices]
ys = [v.co.y for v in bike.data.vertices]
zs = [v.co.z for v in bike.data.vertices]
print('TRIANGLES', len(bike.data.polygons), 'VERTS', len(bike.data.vertices))
print('BOUNDS x %.3f..%.3f y %.3f..%.3f z %.3f..%.3f' % (min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)))

bike['convoyage'] = json.dumps({
    'len': L, 'fo': L / 2 - FX, 'wb': WB, 'tyre': TYRE_R, 'tyreW': TW_R,
    'halfTrack': [0.0, 0.0], 'bike': True,
})
bpy.ops.export_scene.gltf(filepath=out_glb, export_format='GLB', use_selection=True, export_apply=True,
                          export_yup=True, export_materials='EXPORT', export_normals=True, export_texcoords=False,
                          export_extras=True)

# ---- preview ---------------------------------------------------------------------------------------------------
if preview:
    sc = bpy.context.scene
    sc.render.engine = 'BLENDER_WORKBENCH'
    sc.display.shading.light = 'STUDIO'
    sc.display.shading.color_type = 'MATERIAL'
    sc.display.shading.show_specular_highlight = True
    sc.display.shading.show_cavity = True
    sc.render.resolution_x, sc.render.resolution_y = 900, 520
    sc.render.film_transparent = False
    world = bpy.data.worlds.new('w')
    sc.world = world
    world.color = (0.55, 0.58, 0.62)
    cam_data = bpy.data.cameras.new('cam')
    cam_data.lens = 60
    cam = bpy.data.objects.new('cam', cam_data)
    bpy.context.collection.objects.link(cam)
    sc.camera = cam
    shots = (((2.9, -2.9, 1.3), (0, 0, 0.55)), ((0, -4.2, 0.6), (0, 0, 0.55)),
             ((-2.8, -2.6, 1.5), (0, 0, 0.55)), ((3.8, 0, 0.9), (0, 0, 0.55)),
             ((1.9, -1.2, 1.5), (0.2, 0, 0.75)), ((0, 4.2, 0.6), (0, 0, 0.55)))
    for i, (pos, look) in enumerate(shots):
        cam.location = pos
        cam.rotation_euler = (Vector(look) - Vector(pos)).to_track_quat('-Z', 'Y').to_euler()
        sc.render.filepath = preview.replace('.png', f'_{i}.png')
        bpy.ops.render.render(write_still=True)
