"""
Builds one Convoyage motorcycle in Blender from a JSON definition, in the PS1-era style:
flat slab bodywork with chamfered edges, angular fairings, low-poly wheels and hard flat shading.
Exported as GLB with the same material names the app already understands. Wheels ARE included
(the app does not place wheels on a bike).

  blender -b --factory-startup --python build_bike.py -- yamaha-mt-07.json out.glb preview.png

Coordinates: metres, +X forward (nose at +L/2), +Y left, +Z up, ground at Z=0. The glTF exporter
turns this into the app's frame (x forward, y up, z right). Materials: paint, glass, dark, trim,
chrome, head, tail, fog, plate, black, accent (a fixed livery colour that is not the user's paint),
rim (the user's wheel colour). The right-hand side of the bike is -Y.

The JSON gives the published numbers (length, width, height, wheelbase, tyres) and the style
("naked" or "sport"); the shapes themselves are drawn here per style, in metres from those numbers.
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
FX = L / 2 - RF - spec.get('noseOverhang', 0.0)
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
    bsdf.inputs['Roughness'].default_value = 0.42 if name in ('paint', 'chrome', 'glass', 'accent') else 0.7
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


def S(x, zb, zt, hw, p=2.4, yc=0.0, top=1.0, bot=1.0):
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


def pipe(points, r, mat, n=5, name='pipe', caps=True, sharp=SHARP, rs=None):
    P = [Vector(p) for p in points]
    rings = []
    nrm = Vector((0, 0, 1))
    for i, p in enumerate(P):
        t = (P[min(i + 1, len(P) - 1)] - P[max(i - 1, 0)]).normalized()
        nrm, bi = frame_at(t, nrm)
        rr = rs[i] if rs else r
        rings.append([tuple(p + (nrm * math.cos(2 * math.pi * k / n) + bi * math.sin(2 * math.pi * k / n)) * rr) for k in range(n)])
    return build_rings(rings, mat, name, caps, caps, sharp)


def tube(p0, p1, r, mat, n=5, r1=None):
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


def lathe(cx, cz, profile, mat, seg=8, y0=0.0, name='lathe', sharp=SHARP, closed=True):
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


def disc(cx, cz, y, r0, r1, t, mat, seg=8):
    return lathe(cx, cz, [(r0, -t / 2), (r1, -t / 2), (r1, t / 2), (r0, t / 2)], mat, seg, y0=y, sharp=math.radians(60))


def offset_poly(pts, d):
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


def prism(profile, hw, mat, chamfer=0.02, name='prism', sharp=math.radians(18)):
    """A PS1 slab: a side-profile polygon extruded to +/-hw with chamfered edges and flat sides."""
    pts = list(profile)
    if area(pts) < 0:
        pts = list(reversed(pts))
    ins = offset_poly(pts, chamfer)
    rings = [
        [(x, -hw, z) for x, z in pts],
        [(x, -hw + chamfer, z) for x, z in ins],
        [(x, hw - chamfer, z) for x, z in ins],
        [(x, hw, z) for x, z in pts],
    ]
    return build_rings(rings, mat, name, True, True, sharp)


def slab(pts, y_in, y_out, mat, inset=0.012, name='slab', sharp=math.radians(22)):
    """A chamfered side panel in the (x, z) plane, extruded from y_in to y_out."""
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
    for s in (1, -1):
        fn(s)


def arc_band(cx, cz, r, a0, a1, hw, t, mat, n=5, dome=0.008, yc=0.0):
    prof = []
    m = 3
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
    a, b = Vector((p0[0], p0[1])), Vector((p1[0], p1[1]))
    d = (b - a).normalized()
    nn = Vector((-d.y, d.x))
    q = [a + nn * w0 / 2, b + nn * w1 / 2, b - nn * w1 / 2, a - nn * w0 / 2]
    verts = [(v.x, y - t / 2, v.y) for v in q] + [(v.x, y + t / 2, v.y) for v in q]
    faces = [(0, 1, 2, 3), (7, 6, 5, 4), (0, 4, 5, 1), (1, 5, 6, 2), (2, 6, 7, 3), (3, 7, 4, 0)]
    return mesh('spoke', verts, faces, mat, math.radians(50))


def fork_axis(length):
    return (FX - math.sin(RAKE) * length, 0, RF + math.cos(RAKE) * length)


# ---- wheels: low-poly, flat-sided, with flat spokes and the user's rim colour --------------------------------

def wheel(x, r, tw, front, pairs, rim_mat='rim'):
    z = r
    rim = 0.2159
    hw = tw / 2
    prof = [
        (rim + 0.010, -hw), (r - 0.015, -hw * 0.96), (r, -hw * 0.55),
        (r, hw * 0.55), (r - 0.015, hw * 0.96), (rim + 0.010, hw),
    ]
    lathe(x, z, list(reversed(prof)), 'dark', seg=8, name='tyre', sharp=math.radians(70))
    rw = tw * 0.55
    lathe(x, z, [(rim - 0.018, -rw / 2), (rim + 0.006, -rw / 2), (rim + 0.006, -rw / 2 + 0.008), (rim - 0.010, -rw / 2 + 0.012),
                 (rim - 0.010, rw / 2 - 0.012), (rim + 0.006, rw / 2 - 0.008), (rim + 0.006, rw / 2), (rim - 0.018, rw / 2)],
          rim_mat, seg=8, name='rim', sharp=math.radians(50))
    hub_w = tw * 0.70
    lathe(x, z, [(0.012, -hub_w / 2), (0.055, -hub_w / 2), (0.060, -0.02), (0.060, 0.02), (0.055, hub_w / 2), (0.012, hub_w / 2)],
          rim_mat, seg=6, name='hub', closed=False)
    for i in range(pairs):
        base = 2 * math.pi * i / pairs + (math.pi / 2 if front else 0.3)
        for side in (-1, 1):
            a_hub = base + side * 0.10
            a_rim = base + side * (0.30 if pairs <= 3 else 0.17)
            p0 = (x + 0.050 * math.cos(a_hub), z + 0.050 * math.sin(a_hub))
            p1 = (x + (rim - 0.012) * math.cos(a_rim), z + (rim - 0.012) * math.sin(a_rim))
            beam(p0, p1, 0.038, 0.024, 0.026, rim_mat)
    if front:
        disc(x, z, -(hw * 0.55 + 0.020), 0.100, 0.150, 0.006, 'chrome', seg=8)
    else:
        disc(x, z, -(hw * 0.55 + 0.015), 0.070, 0.122, 0.005, 'chrome', seg=8)
        disc(x, z, hw * 0.55 + 0.020, 0.050, 0.105, 0.008, 'trim', seg=6)


wheel(FX, RF, TW_F, True, spec.get('spokePairs', 5))
wheel(RX, RR, TW_R, False, spec.get('spokePairs', 5))

# ---- common running gear: flat fork wedges, swingarm, chain, pegs -------------------------------------------

TOP = spec.get('topClamp', 0.70)
fork_y = TW_F / 2 + 0.05
UPV = Vector((-math.sin(RAKE), 0, math.cos(RAKE)))
PERP = Vector((math.cos(RAKE), 0, math.sin(RAKE)))

for s in (1, -1):
    ax = Vector((FX, 0, RF))
    top = ax + UPV * (TOP + 0.06)
    # lower leg: dark, from the axle to the slider top
    p0 = ax - PERP * 0.026; p1 = ax + PERP * 0.026
    p2 = ax + UPV * 0.30 + PERP * 0.032; p3 = ax + UPV * 0.30 - PERP * 0.032
    slab([(p0.x, p0.z), (p1.x, p1.z), (p2.x, p2.z), (p3.x, p3.z)], s * (fork_y - 0.022), s * (fork_y + 0.022),
         'black', inset=0.006, name='forklow')
    # upper tube: chrome/gold, from the slider to the top clamp
    q0 = ax + UPV * 0.285 - PERP * 0.020; q1 = ax + UPV * 0.285 + PERP * 0.020
    q2 = top + PERP * 0.016; q3 = top - PERP * 0.016
    slab([(q0.x, q0.z), (q1.x, q1.z), (q2.x, q2.z), (q3.x, q3.z)], s * (fork_y - 0.018), s * (fork_y + 0.018),
         'chrome', inset=0.005, name='forkup')
    obox((FX, s * fork_y, RF), (0.06, 0.05, 0.075), 'black', bevel=0.008)
    cal = ax + Vector((-0.115, 0, 0.07))
    obox((cal.x, s * (TW_F / 2 + 0.035), cal.z), (0.05, 0.045, 0.11), 'trim', rot=(0, math.radians(-40), 0), bevel=0.010)

bot = Vector(fork_axis(TOP - 0.15)); topc = Vector(fork_axis(TOP))
for c, h in ((bot, 0.035), (topc, 0.032)):
    obox(tuple(c + Vector((0.005, 0, 0))), (0.075, 2 * fork_y + 0.06, h), 'trim', rot=(0, -RAKE, 0), bevel=0.010)
HEAD = Vector(fork_axis(TOP - 0.08)) - Vector((0.03, 0, 0))
tube(tuple(Vector(fork_axis(TOP - 0.20)) - Vector((0.035, 0, 0))), tuple(Vector(fork_axis(TOP)) - Vector((0.035, 0, 0))), 0.032, 'trim', n=5)

PIVOT = Vector((spec.get('pivotX', -0.17), 0, spec.get('pivotZ', 0.44)))
sa_y = TW_R / 2 + 0.035
for s in (1, -1):
    a = Vector((PIVOT.x, s * sa_y, PIVOT.z)); b = Vector((RX, s * sa_y, RR))
    d = (b - a); d.normalize()
    nz = Vector((-d.z, 0, d.x))
    pts = [a + nz * 0.055, b + nz * 0.028, b - nz * 0.028, a - nz * 0.060]
    slab([(p.x, p.z) for p in pts], s * (sa_y - 0.022), s * (sa_y + 0.022), spec.get('swingarm', 'trim'), inset=0.006)
    obox((RX, s * (sa_y + 0.012), RR), (0.05, 0.03, 0.05), 'chrome', bevel=0.006)
obox((PIVOT.x + 0.04, 0, PIVOT.z), (0.08, 2 * sa_y + 0.02, 0.09), spec.get('swingarm', 'trim'), bevel=0.015)
cy = TW_R / 2 + 0.06
fs = Vector((PIVOT.x + 0.10, cy, PIVOT.z))
tube((fs.x, cy, fs.z + 0.04), (RX, cy, RR + 0.105), 0.008, 'dark', n=4)
tube((fs.x, cy, fs.z - 0.04), (RX, cy, RR - 0.105), 0.008, 'dark', n=4)
obox((fs.x + 0.02, cy - 0.01, fs.z), (0.12, 0.012, 0.12), 'dark', bevel=0.025)


# ---- the PS1 naked bike (MT-07) -------------------------------------------------------------------------------

def naked():
    hx, hz = HEAD.x, HEAD.z
    # Steel frame: two flat silver beams from the steering head to the pivot, plus subframe rails.
    for s in (1, -1):
        slab([(hx - 0.03, hz + 0.03), (hx + 0.02, hz + 0.06), (PIVOT.x + 0.10, PIVOT.z + 0.12), (PIVOT.x - 0.02, PIVOT.z - 0.02)],
             s * 0.105, s * 0.132, 'chrome', inset=0.008, name='frame')
        obox((PIVOT.x - 0.36, s * 0.095, 0.83), (0.46, 0.030, 0.030), 'trim', rot=(0, math.radians(9), 0), bevel=0.005)
        obox((-0.10, s * 0.17, 0.34), (0.05, 0.12, 0.025), 'black', bevel=0.005)
    # Engine: a block, fins, side covers, sump, radiator.
    obox((0.12, 0, 0.48), (0.44, 0.30, 0.34), 'dark', bevel=0.02)
    for i in range(4):
        obox((0.30 + i * 0.008, 0, 0.56 + i * 0.032), (0.16, 0.33, 0.012), 'trim', rot=(0, math.radians(-18), 0), bevel=0.004)
    obox((0.31, 0, 0.70), (0.13, 0.26, 0.06), 'black', rot=(0, math.radians(-18), 0), bevel=0.010)
    lathe(0.08, 0.42, [(0.02, -0.030), (0.10, -0.030), (0.11, 0.0), (0.10, 0.030), (0.02, 0.030)], 'trim', seg=8, y0=-0.17, name='clutch')
    lathe(0.04, 0.42, [(0.02, -0.030), (0.09, -0.030), (0.10, 0.0), (0.09, 0.030), (0.02, 0.030)], 'trim', seg=8, y0=0.17, name='generator')
    obox((0.08, 0, 0.28), (0.34, 0.24, 0.10), 'black', bevel=0.015)
    obox((0.42, 0, 0.58), (0.04, 0.34, 0.26), 'trim', rot=(0, math.radians(-12), 0), bevel=0.006)
    # Tank: a flat slab with a chamfered top, falling to the seat.
    prism([(0.42, 0.72), (0.44, 0.90), (0.28, 1.01), (0.02, 1.04), (-0.16, 0.96), (-0.18, 0.78), (0.08, 0.70)],
          0.19, 'paint', 0.025, name='tank')
    # Seat, pillion and the pointed PS1 tail.
    prism([(-0.16, 0.90), (-0.16, 0.96), (-0.55, 0.98), (-0.55, 0.90)], 0.14, 'black', 0.012, name='seat')
    prism([(-0.48, 0.82), (-0.50, 0.94), (-0.64, 0.99), (-0.82, 0.94), (-0.84, 0.86), (-0.64, 0.80)],
          0.115, 'paint', 0.018, name='tail')
    prism([(-0.50, 0.95), (-0.52, 0.98), (-0.64, 1.00), (-0.64, 0.95)], 0.10, 'black', 0.008, name='pillion')
    # Flat tank scoops: the MT-07's angular side covers.
    for s in (1, -1):
        slab([(0.36, 0.84), (0.28, 0.92), (0.02, 0.88), (0.04, 0.77), (0.24, 0.75)], s * 0.190, s * 0.205,
             'black', inset=0.008, name='scoop')
        slab([(0.38, 0.86), (0.30, 0.94), (0.06, 0.90), (0.08, 0.83), (0.26, 0.81)], s * 0.205, s * 0.218,
             'paint', inset=0.006, name='scoopcap')
    # Round headlight: a 10-sided drum with a flat lens.
    lx, lz, lr = 0.62, 0.80, 0.10
    loft([S(lx, lz - lr, lz + lr, lr, 2.0), S(lx + 0.055, lz - lr, lz + lr, lr, 2.0)], 'black', n=10, name='lamp')
    loft([S(lx + 0.056, lz - lr * 0.88, lz + lr * 0.88, lr * 0.88, 2.0),
          S(lx + 0.062, lz - lr * 0.88, lz + lr * 0.88, lr * 0.88, 2.0)], 'head', n=10, name='lens')
    slab([(lx - 0.10, lz + 0.08), (lx - 0.02, lz + 0.12), (lx + 0.04, lz + 0.04), (lx - 0.04, lz + 0.02)],
         -0.10, 0.10, 'black', inset=0.008, name='cowl')
    for s in (1, -1):
        obox((lx - 0.02, s * 0.12, lz + 0.02), (0.04, 0.05, 0.03), 'fog', bevel=0.006)
    obox((hx + 0.02, 0, hz + 0.10), (0.06, 0.12, 0.06), 'black', rot=(0, math.radians(-50), 0), bevel=0.008)
    # Wide flat bar, grips, levers and flat mirrors.
    bx, bz = hx - 0.08, hz + 0.16
    obox((bx, 0, bz), (0.030, 0.68, 0.030), 'black', bevel=0.008)
    for s in (1, -1):
        obox((bx, s * 0.30, bz), (0.045, 0.09, 0.045), 'black', bevel=0.008)
        tube((bx, s * 0.12, bz), (bx - 0.02, s * 0.31, bz + 0.06), 0.008, 'black', n=4)
        obox((bx - 0.03, s * 0.325, bz + 0.075), (0.03, 0.055, 0.038), 'black', bevel=0.008)
        obox((bx + 0.03, s * 0.22, bz), (0.02, 0.09, 0.008), 'chrome', bevel=0.002)
    # Exhaust: two low-poly headers into the stubby right-hand can.
    for k, y in enumerate((0.05, -0.05)):
        pipe([(0.36, y, 0.56), (0.42, y, 0.44), (0.38 - 0.02 * k, y * 1.2, 0.28), (0.24, y * 0.6 - 0.02, 0.20),
              (0.06, -0.12, 0.20)], 0.018, 'chrome', n=5)
    pipe([(0.06, -0.12, 0.20), (-0.16, -0.18, 0.32), (-0.34, -0.20, 0.46)], 0.020, 'chrome', n=5)
    loft([S(-0.34, 0.38, 0.54, 0.090, 2.0, yc=-0.20), S(-0.52, 0.40, 0.56, 0.100, 2.0, yc=-0.20),
          S(-0.66, 0.42, 0.56, 0.085, 2.0, yc=-0.20)], 'chrome', n=8, name='can')
    obox((-0.67, -0.20, 0.49), (0.012, 0.11, 0.10), 'dark', bevel=0.006)
    # Tail light, plate hanger and indicators.
    obox((-0.845, 0, 0.92), (0.02, 0.08, 0.035), 'tail', rot=(0, math.radians(-15), 0), bevel=0.004)
    slab([(-0.70, 0.82), (-0.80, 0.82), (-0.96, 0.62), (-0.94, 0.57), (-0.88, 0.60)], -0.03, 0.03,
         'black', inset=0.005, name='hanger')
    obox((-0.97, 0, 0.54), (0.012, 0.19, 0.12), 'plate', rot=(0, math.radians(12), 0))
    for s in (1, -1):
        tube((-0.93, s * 0.03, 0.64), (-0.94, s * 0.13, 0.65), 0.006, 'black', n=4)
        obox((-0.945, s * 0.14, 0.65), (0.025, 0.015, 0.012), 'fog')
    # Flat fenders.
    arc_band(FX, RF, RF + 0.025, 45, 125, TW_F / 2 + 0.012, 0.007, 'paint', n=5)
    arc_band(RX, RR, RR + 0.015, 60, 115, TW_R / 2 + 0.012, 0.005, 'black', n=4)
    # Shock and pegs.
    obox((PIVOT.x - 0.02, 0, PIVOT.z + 0.12), (0.05, 0.05, 0.26), 'trim', rot=(0, math.radians(15), 0), bevel=0.008)


# ---- the PS1 sport bike (ZX-6R 636) ---------------------------------------------------------------------------

def sport():
    hx, hz = HEAD.x, HEAD.z
    # Perimeter frame spars, visible between the fairing and the tank.
    for s in (1, -1):
        slab([(hx - 0.03, hz + 0.02), (hx + 0.02, hz + 0.05), (PIVOT.x + 0.10, PIVOT.z + 0.14), (PIVOT.x - 0.04, PIVOT.z - 0.02)],
             s * 0.100, s * 0.140, 'trim', inset=0.008, name='spar')
        obox((PIVOT.x - 0.34, s * 0.08, 0.88), (0.40, 0.025, 0.030), 'trim', rot=(0, math.radians(10), 0), bevel=0.004)
        obox((-0.10, s * 0.17, 0.36), (0.05, 0.12, 0.025), 'black', bevel=0.005)
    # Engine and side covers.
    obox((0.10, 0, 0.47), (0.44, 0.30, 0.34), 'dark', bevel=0.02)
    for i in range(4):
        obox((0.28 + i * 0.008, 0, 0.55 + i * 0.032), (0.16, 0.33, 0.012), 'trim', rot=(0, math.radians(-18), 0), bevel=0.004)
    lathe(0.06, 0.42, [(0.02, -0.030), (0.10, -0.030), (0.11, 0.0), (0.10, 0.030), (0.02, 0.030)], 'trim', seg=8, y0=-0.17, name='clutch')
    lathe(0.02, 0.42, [(0.02, -0.030), (0.09, -0.030), (0.10, 0.0), (0.09, 0.030), (0.02, 0.030)], 'trim', seg=8, y0=0.17, name='generator')
    # Belly pan and the lime KRT streak.
    prism([(0.45, 0.26), (0.48, 0.48), (0.30, 0.56), (-0.02, 0.52), (-0.10, 0.30), (0.10, 0.20)],
          0.17, 'black', 0.020, name='belly')
    for s in (1, -1):
        slab([(0.44, 0.44), (0.40, 0.40), (0.10, 0.34), (-0.06, 0.26), (-0.07, 0.30), (0.10, 0.40), (0.38, 0.47)],
             s * 0.172, s * 0.182, 'accent', inset=0.004, name='streak')
    # Upper fairing: a pointed PS1 slab over the front wheel.
    prism([(0.10, 0.60), (0.14, 0.84), (0.40, 0.98), (0.70, 1.04), (0.80, 0.96), (0.82, 0.82), (0.70, 0.68),
           (0.45, 0.60), (0.28, 0.58)], 0.22, 'paint', 0.030, name='fairing')
    # Windscreen: a tall flat panel raked back over the clocks.
    prism([(0.28, 1.00), (0.32, 1.07), (0.50, 1.12), (0.58, 1.05), (0.46, 0.98)], 0.13, 'glass', 0.012, name='screen')
    # Tank and the high, pointed tail.
    prism([(0.36, 0.84), (0.38, 1.00), (0.18, 1.06), (-0.06, 1.04), (-0.16, 0.94), (-0.14, 0.84), (0.12, 0.80)],
          0.19, 'paint', 0.024, name='tank')
    prism([(-0.14, 0.90), (-0.14, 0.96), (-0.52, 0.98), (-0.52, 0.90)], 0.14, 'black', 0.012, name='seat')
    prism([(-0.42, 0.86), (-0.46, 0.98), (-0.62, 1.03), (-0.82, 0.98), (-0.84, 0.90), (-0.64, 0.84)],
          0.115, 'paint', 0.018, name='tail')
    prism([(-0.48, 0.99), (-0.50, 1.02), (-0.64, 1.04), (-0.64, 0.99)], 0.10, 'black', 0.008, name='pillion')
    for s in (1, -1):
        slab([(-0.16, 0.88), (-0.60, 0.94), (-0.80, 1.00), (-0.80, 1.02), (-0.60, 0.97), (-0.18, 0.92)],
             s * 0.116, s * 0.124, 'accent', inset=0.003, name='tailstripe')
        # Black side vent on the fairing flank.
        obox((0.56, s * 0.225, 0.80), (0.16, 0.012, 0.10), 'black', rot=(0, math.radians(-8), 0), bevel=0.004)
        # Twin headlights and the black bezel, sitting on the nose edge.
        obox((0.807, s * 0.10, 0.86), (0.012, 0.115, 0.115), 'black', rot=(0, math.radians(-10), 0), bevel=0.004)
        obox((0.815, s * 0.10, 0.86), (0.012, 0.10, 0.10), 'head', rot=(0, math.radians(-10), 0), bevel=0.006)
        # Mirror stalk and flat mirror.
        tube((0.52, s * 0.20, 1.00), (0.48, s * 0.29, 1.06), 0.008, 'black', n=4)
        obox((0.47, s * 0.315, 1.07), (0.03, 0.05, 0.04), 'black', bevel=0.006)
        obox((0.47, s * 0.32, 1.07), (0.008, 0.035, 0.008), 'fog', bevel=0.002)
    # Ram-air intake between the lamps.
    obox((0.83, 0, 0.84), (0.012, 0.07, 0.06), 'black', rot=(0, math.radians(-10), 0), bevel=0.004)
    # Clocks and clip-ons.
    obox((hx + 0.03, 0, hz + 0.06), (0.06, 0.14, 0.05), 'black', rot=(0, math.radians(-50), 0), bevel=0.008)
    for s in (1, -1):
        obox((topc.x - 0.03, s * 0.20, topc.z - 0.04), (0.20, 0.025, 0.025), 'black', rot=(0, 0, math.radians(-10 * s)), bevel=0.006)
        obox((topc.x - 0.12, s * 0.29, topc.z - 0.05), (0.05, 0.09, 0.03), 'black', bevel=0.008)
    # Long right-hand silencer with a chrome end cap.
    pipe([(0.18, -0.06, 0.20), (-0.02, -0.08, 0.18), (-0.14, -0.15, 0.34), (-0.26, -0.20, 0.50)],
         0.024, 'chrome', n=5)
    loft([S(-0.26, 0.46, 0.64, 0.095, 2.0, yc=-0.20), S(-0.44, 0.48, 0.66, 0.105, 2.0, yc=-0.20),
          S(-0.66, 0.50, 0.66, 0.090, 2.0, yc=-0.20)], 'chrome', n=8, name='silencer')
    obox((-0.67, -0.20, 0.58), (0.012, 0.11, 0.09), 'dark', bevel=0.006)
    # Tail light, plate and hanger.
    obox((-0.845, 0, 0.94), (0.02, 0.07, 0.03), 'tail', rot=(0, math.radians(-20), 0), bevel=0.004)
    slab([(-0.72, 0.84), (-0.80, 0.84), (-0.97, 0.64), (-0.95, 0.59), (-0.88, 0.62)], -0.03, 0.03,
         'black', inset=0.005, name='hanger')
    obox((-0.975, 0, 0.56), (0.012, 0.19, 0.12), 'plate', rot=(0, math.radians(12), 0))
    for s in (1, -1):
        tube((-0.93, s * 0.03, 0.66), (-0.94, s * 0.13, 0.67), 0.006, 'black', n=4)
        obox((-0.945, s * 0.14, 0.67), (0.025, 0.015, 0.012), 'fog')
    # Flat fenders and the shock.
    arc_band(FX, RF, RF + 0.025, 40, 130, TW_F / 2 + 0.012, 0.007, 'paint', n=5)
    arc_band(RX, RR, RR + 0.015, 60, 120, TW_R / 2 + 0.012, 0.005, 'black', n=4)
    obox((PIVOT.x - 0.02, 0, PIVOT.z + 0.12), (0.05, 0.05, 0.28), 'accent', rot=(0, math.radians(15), 0), bevel=0.008)


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
