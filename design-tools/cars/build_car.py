"""
Builds one Convoyage vehicle in Blender from a JSON definition and exports it as GLB, Gran Turismo 1/2 style:
a smooth-shaded lofted body (~1-2k triangles) with real wheel arches, glass, and lights/grilles projected onto it.

  blender -b --factory-startup --python build_car.py -- cars/nissan-skyline-r33.json out.glb [preview.png]

Coordinates: metres, +X forward (nose at +L/2), +Y left, +Z up, ground at Z=0. The glTF exporter turns this into
the app's frame (x forward, y up, z right). Wheels are not exported; the app draws them at the axles.
Material names are the contract with the app: paint, glass, dark, trim, chrome, head, tail, fog, plate, black.
"""
import bpy, bmesh, json, math, sys
from mathutils import Vector
from mathutils.bvhtree import BVHTree

argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
def load(path):
    """A definition, merged over the one it "extends" (same folder): its keys replace the base's, except "profile"
    (merged key by key) and lists named in "add" (appended to the base's)."""
    import os
    d = json.load(open(path, encoding='utf-8'))
    if 'extends' not in d:
        return d
    base = load(os.path.join(os.path.dirname(path), d['extends'] + '.json'))
    out = dict(base)
    for k, v in d.items():
        if k == 'profile':
            out['profile'] = {**base.get('profile', {}), **v}
        elif k in d.get('add', []):
            out[k] = base.get(k, []) + v
        else:
            out[k] = v
    return out


spec = load(argv[0])
out_glb = argv[1]
preview = argv[2] if len(argv) > 2 else None

L, W, H, WB = (v / 1000.0 for v in spec['mm'])
FO = spec['fo']
TYRE = spec['tyre']
HW = W / 2


def curve(s):
    pts, marks = [], {}
    for tok in s.split():
        dp, v = tok.split(':')
        if dp[-1].isalpha():
            marks[dp[-1]] = float(dp[:-1]); dp = dp[:-1]
        pts.append((float(dp), float(v)))
    return Curve(pts, set(marks.values())), marks


class Curve(list):
    """Monotone cubic (PCHIP) through the points: slopes stay continuous, so panels don't
    terrace or dent between keys. Marked points (cowl, roof, boot) keep a hard crease."""

    def __init__(self, pts, kinks=()):
        super().__init__(pts)
        n = len(pts)
        sec = [(pts[i + 1][1] - pts[i][1]) / max(pts[i + 1][0] - pts[i][0], 1e-6) for i in range(n - 1)]
        self.tl, self.tr = [0.0] * n, [0.0] * n
        for i in range(n):
            if i == 0 or i == n - 1:
                t = sec[0] if i == 0 else sec[-1]
                self.tl[i] = self.tr[i] = t if n > 1 else 0.0
                continue
            a, b = sec[i - 1], sec[i]
            if pts[i][0] in kinks:
                self.tl[i], self.tr[i] = a, b
            elif a * b <= 0:
                self.tl[i] = self.tr[i] = 0.0
            else:
                h0, h1 = pts[i][0] - pts[i - 1][0], pts[i + 1][0] - pts[i][0]
                w0, w1 = 2 * h1 + h0, h1 + 2 * h0
                self.tl[i] = self.tr[i] = (w0 + w1) / (w0 / a + w1 / b)


def at(pts, d):
    if d <= pts[0][0]: return pts[0][1]
    for i, ((a, va), (b, vb)) in enumerate(zip(pts, pts[1:])):
        if d <= b:
            h = b - a
            if h < 1e-6: return vb
            t = (d - a) / h
            if not spec.get('smooth', True) or not isinstance(pts, Curve):
                return va + (vb - va) * t
            m0, m1 = pts.tr[i] * h, pts.tl[i + 1] * h
            t2, t3 = t * t, t * t * t
            return (2 * t3 - 3 * t2 + 1) * va + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * vb + (t3 - t2) * m1
    return pts[-1][1]


P = spec['profile']
TOP, M = curve(P['top'])
BOT, _ = curve(P['bot'])
PLAN, _ = curve(P['plan'])
BELT, _ = curve(P['belt'])
COWL, ROOFF, ROOFR, GEND = M.get('c', 0), M.get('f', 0), M.get('r', 0), M.get('b', 0)
END = TOP[-1][0]
TUMBLE = P.get('tumble', 0.78)
WINDOWS = [tuple(map(float, w.split('-'))) for w in P.get('win', '').split()]
CABIN = ROOFR > ROOFF


def zone(d):
    if not CABIN or d < COWL: return 0
    if d < ROOFF: return 1
    if d < ROOFR: return 2
    if d < GEND: return 3
    return 0


def X(d): return L / 2 - d


# ---- materials ----
COLORS = {
    'paint': (0.75, 0.05, 0.06, 1), 'glass': (0.02, 0.04, 0.07, 1), 'dark': (0.01, 0.01, 0.012, 1),
    'trim': (0.06, 0.065, 0.07, 1), 'chrome': (0.7, 0.72, 0.75, 1), 'head': (0.9, 0.97, 1, 1),
    'tail': (0.9, 0.05, 0.1, 1), 'fog': (1, 0.85, 0.5, 1), 'plate': (0.92, 0.92, 0.9, 1), 'black': (0.0, 0.0, 0.0, 1),
    'red': (0.6, 0.02, 0.04, 1),
}
MATS = {}
for name, col in COLORS.items():
    m = bpy.data.materials.new(name)
    m.diffuse_color = col
    m.use_nodes = True
    bsdf = m.node_tree.nodes.get('Principled BSDF')
    bsdf.inputs['Base Color'].default_value = col
    bsdf.inputs['Roughness'].default_value = 0.25 if name in ('paint', 'glass', 'chrome') else 0.6
    if name in ('head', 'tail', 'fog'):
        bsdf.inputs['Emission Color'].default_value = col
        bsdf.inputs['Emission Strength'].default_value = 1.0
    MATS[name] = m
MAT_INDEX = {n: i for i, n in enumerate(COLORS)}

for o in list(bpy.data.objects): bpy.data.objects.remove(o)


def new_obj(name, bm):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me); bm.free()
    ob = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(ob)
    for n in COLORS: me.materials.append(MATS[n])
    return ob


# ---- body loft ----
def ring(d):
    """Half cross-section from the rocker up to the crown: list of (y, z, material-of-band-above)."""
    top, bot, w = at(TOP, d), at(BOT, d), at(PLAN, d)
    zn = zone(d)
    cabin = zn != 0
    belt = min(at(BELT, d), top - 0.01) if cabin else top - 0.07
    shoulder = min(belt - 0.08, bot + (belt - bot) * 0.72)
    mid = bot + (shoulder - bot) * 0.45
    pts = [
        (w * 0.86, bot), (w * 0.97, min(bot + 0.06, mid)), (w, mid), (w * 0.99, shoulder - 0.05),
        (w * 0.985, shoulder), (w * 0.955, belt),
    ]
    if cabin:
        tw = w * TUMBLE
        pts += [(tw, max(top - 0.075, belt + 0.004)), (tw * 0.9, max(top - 0.025, belt + 0.008)), (tw * 0.55, max(top - 0.004, belt + 0.01))]
    else:
        pts += [(w * 0.86, top - 0.03), (w * 0.62, top - 0.01), (w * 0.3, top - 0.002)]
    pts += [(0.0, top)]
    return pts


def stations():
    keys = set([0.0, END, COWL, ROOFF, ROOFR, GEND, FO, FO + WB])
    for c in (TOP, BOT, PLAN, BELT):
        keys.update(p[0] for p in c if p[0] <= END)
    for a, b in WINDOWS: keys.update((a, b))
    base = sorted(k for k in keys if 0 <= k <= END)
    out = []
    for a, b in zip(base, base[1:]):
        n = int((b - a) / 0.16)
        out.append(a)
        out.extend(a + (b - a) * k / (n + 1) for k in range(1, n + 1))
    out.append(base[-1])
    res = []
    for d in out:
        if not res or d - res[-1] > 0.01: res.append(d)
    return res


def band_material(k, n, d0, d1):
    dm = (d0 + d1) / 2
    zn = zone(dm)
    window = zn != 0 and any(a <= dm <= b for a, b in WINDOWS)
    if k == 5:  # belt to glass top
        return 'glass' if window else 'paint'
    if k == 6:
        return 'paint'
    if k in (7, 8):
        return 'glass' if zn in (1, 3) else 'paint'
    return 'paint'


bm = bmesh.new()
ds = stations()
rings = []
for d in ds:
    half = ring(d)
    # Full ring: +Y side from rocker to crown, then the -Y side back down (crown shared).
    full = [(y, z) for y, z in half] + [(-y, z) for y, z in reversed(half[:-1])]
    rings.append([bm.verts.new((X(d), y, z)) for y, z in full])
n = len(rings[0])
nh = len(ring(ds[0]))
for i in range(len(ds) - 1):
    for k in range(n):
        k2 = (k + 1) % n
        f = bm.faces.new((rings[i][k], rings[i][k2], rings[i + 1][k2], rings[i + 1][k]))
        if k == n - 1:
            mat = 'dark'  # underside
        else:
            band = k if k < nh - 1 else (n - 2 - k)
            mat = band_material(band, nh, ds[i], ds[i + 1])
        f.material_index = MAT_INDEX[mat]
# End caps.
for r, flip in ((rings[0], True), (rings[-1], False)):
    f = bm.faces.new(list(reversed(r)) if flip else r)
    f.material_index = MAT_INDEX['paint']
    if spec.get('cab') and not flip:
        f.material_index = MAT_INDEX['paint']
bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
body = new_obj('body', bm)

# Smooth, with hard edges where the material changes (glass edges, the floor).
for p in body.data.polygons: p.use_smooth = True
bm = bmesh.new(); bm.from_mesh(body.data)
for e in bm.edges:
    if len(e.link_faces) == 2 and e.link_faces[0].material_index != e.link_faces[1].material_index:
        e.smooth = False
    if len(e.link_faces) == 2 and e.calc_face_angle(0) > math.radians(38):
        e.smooth = False
bm.to_mesh(body.data); bm.free()

# ---- wheel arches: cut tunnels across the body; their walls are the wheel wells ----
R_ARCH = TYRE / 2 * 1.1 + 0.015
for ax in (FO, FO + WB):
    bpy.ops.mesh.primitive_cylinder_add(vertices=24, radius=R_ARCH, depth=W + 0.6, location=(X(ax), 0, TYRE / 2 + 0.01), rotation=(math.pi / 2, 0, 0))
    cut = bpy.context.active_object
    cut.data.materials.append(MATS['dark'])
    mod = body.modifiers.new('arch', 'BOOLEAN')
    mod.operation = 'DIFFERENCE'; mod.object = cut; mod.solver = 'EXACT'
    mod.material_mode = 'TRANSFER'
    bpy.context.view_layer.objects.active = body
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.data.objects.remove(cut)
# Material indices after TRANSFER can append a duplicate slot; fold it back onto ours.
names = [m.name for m in body.data.materials]
for p in body.data.polygons:
    nm = names[p.material_index].split('.')[0]
    p.material_index = MAT_INDEX.get(nm, MAT_INDEX['dark'])



# ---- recesses: outlines seen from the front or rear, punched into the body ----
def recess(pts, view, depth, wall, back, reach=0.3):
    """Grilles, intakes, lamp housings: the outline is cut in to [depth] below the surface it lands on,
    so the back follows the bumper's curve. Walls and back take their own materials."""
    tmp = BVHTree.FromObject(body, bpy.context.evaluated_depsgraph_get())
    sgn = 1 if view == 'front' else -1
    face_x = []
    for u, v in pts:
        h = tmp.ray_cast(Vector((sgn * (L / 2 + 1), u, v)), Vector((-sgn, 0, 0)))
        face_x.append(h[0].x if h[0] is not None else None)
    hits = [x for x in face_x if x is not None]
    if not hits: return
    # Rays that graze past the corner land far down the flank; keep the recess near the face.
    lim = max(hits, key=lambda x: sgn * x) - sgn * reach
    face_x = [None if x is None or sgn * x < sgn * lim else x for x in face_x]
    inner = lim
    bm = bmesh.new()
    a = [bm.verts.new((sgn * (L / 2 + 0.5), u, v)) for u, v in pts]
    b = [bm.verts.new(((x if x is not None else inner) - sgn * depth, u, v)) for (u, v), x in zip(pts, face_x)]
    n = len(pts)
    for i in range(n):
        f = bm.faces.new((a[i], a[(i + 1) % n], b[(i + 1) % n], b[i])); f.material_index = MAT_INDEX[wall]
    fa = bm.faces.new(a); fa.material_index = MAT_INDEX[wall]
    fb = bm.faces.new(list(reversed(b))); fb.material_index = MAT_INDEX[back]
    bmesh.ops.triangulate(bm, faces=[fa, fb])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    cut = new_obj('cut', bm)
    mod = body.modifiers.new('recess', 'BOOLEAN')
    mod.operation = 'DIFFERENCE'; mod.object = cut; mod.solver = 'EXACT'
    mod.material_mode = 'TRANSFER'
    bpy.context.view_layer.objects.active = body
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.data.objects.remove(cut)


for c in spec.get('cuts', []):
    view = c.get('view', 'front')
    pts = [tuple(p) for p in (c['pts'] if 'pts' in c else outline(c))]
    shapes = [pts] + ([[(-u, v) for u, v in reversed(pts)]] if c.get('mirror', True) else [])
    for sh in shapes:
        recess(sh, view, c.get('depth', 0.04), c.get('wall', 'black'), c.get('back', 'trim'), c.get('reach', 0.3))
names = [m.name for m in body.data.materials]
for p in body.data.polygons:
    nm = names[p.material_index].split('.')[0]
    p.material_index = MAT_INDEX.get(nm, MAT_INDEX['dark'])

bvh = BVHTree.FromObject(body, bpy.context.evaluated_depsgraph_get())


# ---- features projected onto the body ----
VIEWS = {
    # view: (2D (u, v) -> ray origin, ray direction). front/rear: u across, v up. side: u back from the nose, v up.
    # top: u back from the nose, v across.
    'front': (lambda u, v: Vector((L / 2 + 1, u, v)), Vector((-1, 0, 0))),
    'rear': (lambda u, v: Vector((-L / 2 - 1, u, v)), Vector((1, 0, 0))),
    'left': (lambda u, v: Vector((X(u), 2.5, v)), Vector((0, -1, 0))),
    'right': (lambda u, v: Vector((X(u), -2.5, v)), Vector((0, 1, 0))),
    'top': (lambda u, v: Vector((X(u), v, 3.0)), Vector((0, 0, -1))),
}


def project_feature(name, points, view, mat, offset=0.012):
    """A 2D outline seen from [view]: filled, subdivided into ~4 cm cells, and every vertex dropped onto the body
    along the view and lifted off it along the surface normal, so it follows the curvature like a decal."""
    origin_of, ray = VIEWS[view]
    bm = bmesh.new()
    vs = [bm.verts.new((u, v, 0.0)) for u, v in points]
    face = bm.faces.new(vs)
    bmesh.ops.triangulate(bm, faces=[face])
    for _ in range(3):
        long = [e for e in bm.edges if e.calc_length() > 0.04]
        if not long: break
        bmesh.ops.subdivide_edges(bm, edges=long, cuts=1, use_grid_fill=True)
        bmesh.ops.triangulate(bm, faces=bm.faces[:])
    for v in bm.verts:
        h = bvh.ray_cast(origin_of(v.co.x, v.co.y), ray)
        if h[0] is None:
            bm.free(); return None
        n = h[1].normalized()
        if n.dot(ray) > 0: n = -n
        v.co = h[0] + n * offset
    for f in bm.faces: f.material_index = MAT_INDEX[mat]
    return new_obj(name, bm)


def line_outline(pts, w):
    """A polyline as a thin closed outline [w] wide."""
    left, right = [], []
    for i, (u, v) in enumerate(pts):
        a = pts[max(i - 1, 0)]; b = pts[min(i + 1, len(pts) - 1)]
        du, dv = b[0] - a[0], b[1] - a[1]
        ln = math.hypot(du, dv) or 1
        nu, nv = -dv / ln * w / 2, du / ln * w / 2
        left.append((u + nu, v + nv)); right.append((u - nu, v - nv))
    return left + list(reversed(right))


def outline(shape):
    """Feature outlines: polygons in (across, up), or circles / rounded rectangles described by numbers."""
    t = shape['type']
    if t == 'poly':
        return [tuple(p) for p in shape['pts']]
    if t == 'circle':
        cy, cz, r = shape['c'][0], shape['c'][1], shape['r']
        return [(cy + r * math.cos(a), cz + r * math.sin(a)) for a in (i * 2 * math.pi / 10 for i in range(10))]
    if t == 'line':
        return line_outline([tuple(p) for p in shape['pts']], shape.get('w', 0.008))
    if t == 'ring':
        cy, cz, r, w = shape['c'][0], shape['c'][1], shape['r'], shape.get('w', 0.012)
        outer = [(cy + r * math.cos(a), cz + r * math.sin(a)) for a in (i * 2 * math.pi / 16 for i in range(16))]
        inner = [(cy + (r - w) * math.cos(a), cz + (r - w) * math.sin(a)) for a in (i * 2 * math.pi / 16 for i in range(16))]
        # Two half rings, each a simple polygon.
        return None, [outer[:9] + list(reversed(inner[:9])), outer[8:] + [outer[0]] + list(reversed(inner[8:] + [inner[0]]))]
    if t == 'rect':
        y0, z0, y1, z1 = shape['box']
        return [(y0, z0), (y1, z0), (y1, z1), (y0, z1)]
    raise ValueError(t)


parts = []
for group, default_view in (('front', 'front'), ('rear', 'rear'), ('side', 'side'), ('top', 'top')):
    for feat in spec.get(group, []):
        view = feat.get('view', default_view)
        shape = outline(feat)
        polys = shape[1] if isinstance(shape, tuple) else [shape]
        for pts in polys:
            if view == 'side':
                only = feat.get('only')
                jobs = [(only, pts)] if only else [('left', pts), ('right', pts)]
            elif view == 'top':
                jobs = [('top', pts)] + ([('top', [(u, -v) for u, v in pts])] if feat.get('mirror', True) else [])
            else:
                jobs = [(view, pts)] + ([(view, [(-u, v) for u, v in pts])] if feat.get('mirror', True) else [])
            for vw, p2 in jobs:
                ob = project_feature('feat', p2, vw, feat['mat'], feat.get('offset', 0.01))
                if ob is None:
                    print('MISS', group, feat.get('type'), feat.get('mat')); continue
                parts.append(ob)


def box(name, c, size, mat, rot=(0, 0, 0)):
    bpy.ops.mesh.primitive_cube_add(size=1, location=c, rotation=rot)
    ob = bpy.context.active_object
    ob.scale = size
    bpy.ops.object.transform_apply(scale=True, rotation=True, location=False)
    ob.data.materials.clear()
    for nme in COLORS: ob.data.materials.append(MATS[nme])
    for p in ob.data.polygons: p.material_index = MAT_INDEX[mat]
    parts.append(ob)
    return ob


# Mirrors.
md = COWL + 0.22
mh = at(BELT, md) + 0.07
mw = at(PLAN, md)
for s in (1, -1):
    box('mirror', (X(md), s * (mw * 0.96 + 0.07), mh), (0.09, 0.12, 0.08), 'paint')
    box('mirror_arm', (X(md) - 0.01, s * (mw * 0.96 + 0.01), mh - 0.02), (0.05, 0.05, 0.025), 'trim')

# Wing on posts.
wing = spec.get('wing')
if wing:
    wd, wz, chord, span = wing['d'], wing['h'], wing['chord'], wing['span']
    deck = at(TOP, wd)
    for s in (1, -1):
        box('post', (X(wd), s * span * 0.32, (deck + wz) / 2), (0.08, 0.03, wz - deck + 0.02), 'paint')
        box('plate', (X(wd), s * span / 2, wz + 0.02), (chord + 0.04, 0.012, 0.09), 'paint')
    box('wing', (X(wd), 0, wz), (chord, span, 0.035), 'paint', rot=(0, math.radians(-wing.get('angle', 6)), 0))
# Exhaust tips: a chrome tube with a dark bore, poking out of the rear bumper.
for ex in spec.get('exhaust', []):
    for y in ([ex['y'], -ex['y']] if ex.get('mirror', False) else [ex['y']]):
        h = bvh.ray_cast(Vector((-L / 2 - 1, y, ex['z'])), Vector((1, 0, 0)))
        x0 = h[0].x if h[0] is not None else -L / 2
        bpy.ops.mesh.primitive_cylinder_add(vertices=12, radius=ex['r'], depth=0.16, location=(x0 + 0.02, y, ex['z']), rotation=(0, math.pi / 2, 0))
        ob = bpy.context.active_object
        ob.data.materials.clear()
        for nme in COLORS: ob.data.materials.append(MATS[nme])
        for p in ob.data.polygons: p.material_index = MAT_INDEX['black' if abs(p.normal.x) > 0.9 else 'chrome']
        parts.append(ob)
# Free-placed parts: spoilers, diffusers, vents, splitters. d back from the nose, y across (+ left), z up;
# rot is pitch in degrees (+ lifts the trailing edge); mirror copies to the other side.
for b in spec.get('boxes', []):
    ys = [b.get('y', 0.0), -b.get('y', 0.0)] if b.get('mirror') and b.get('y', 0.0) != 0 else [b.get('y', 0.0)]
    for y in ys:
        box('part', (X(b['d']), y, b['z']), tuple(b['size']), b.get('mat', 'paint'), rot=(math.radians(b.get('roll', 0)) * (1 if y >= 0 else -1), math.radians(b.get('rot', 0)), 0))
lip = spec.get('lip')
if lip:
    box('lip', (X(0.12), 0, at(BOT, 0.1) - 0.01), (0.28, at(PLAN, 0.15) * 1.9, 0.025), 'black')

# ---- join, export ----
for o in bpy.context.selected_objects: o.select_set(False)
for o in [body] + parts: o.select_set(True)
bpy.context.view_layer.objects.active = body
bpy.ops.object.join()
car = bpy.context.active_object
car.name = spec['id']
bm = bmesh.new(); bm.from_mesh(car.data)
bmesh.ops.triangulate(bm, faces=bm.faces)
bm.to_mesh(car.data); bm.free()
for p in car.data.polygons: p.use_smooth = p.use_smooth
print('TRIANGLES', len(car.data.polygons))
# What the app needs to put the wheels in the arches: axle positions (metres back from the nose) and half tracks.
car['convoyage'] = json.dumps({
    'len': L, 'fo': FO, 'wb': WB, 'tyre': TYRE, 'tyreW': spec.get('tyreW', 0.235),
    'halfTrack': [at(PLAN, ax) - spec.get('tyreW', 0.235) / 2 - 0.03 for ax in (FO, FO + WB)],
})
bpy.ops.export_scene.gltf(filepath=out_glb, export_format='GLB', use_selection=True, export_apply=True,
                          export_yup=True, export_materials='EXPORT', export_normals=True, export_texcoords=False, export_extras=True)

# ---- preview: Workbench, a 3/4 front and a side view ----
if preview:
    for s in (1, -1):  # stand-in wheels for the picture only
        for ax in (FO, FO + WB):
            bpy.ops.mesh.primitive_cylinder_add(vertices=16, radius=TYRE / 2, depth=spec.get('tyreW', 0.24), location=(X(ax), s * (at(PLAN, ax) - 0.14), TYRE / 2), rotation=(math.pi / 2, 0, 0))
            bpy.context.active_object.data.materials.append(MATS['dark'])
    sc = bpy.context.scene
    sc.render.engine = 'BLENDER_WORKBENCH'
    sc.display.shading.light = 'STUDIO'
    sc.display.shading.color_type = 'MATERIAL'
    sc.display.shading.show_specular_highlight = True
    sc.render.resolution_x, sc.render.resolution_y = 640, 360
    sc.render.film_transparent = False
    cam_data = bpy.data.cameras.new('cam'); cam_data.lens = 50
    cam = bpy.data.objects.new('cam', cam_data); bpy.context.collection.objects.link(cam); sc.camera = cam
    for i, (pos, look) in enumerate((((5.2, -5.2, 2.0), (0, 0, 0.6)), ((0, -8.5, 1.0), (0, 0, 0.6)), ((-5.2, -4.6, 2.0), (0, 0, 0.6)), ((6.5, -2.6, 1.3), (0, 0, 0.55)), ((5.6, -3.4, 0.95), (0, 0, 0.5)), ((9.0, 0, 0.6), (0, 0, 0.55)))):
        cam.location = pos
        cam.rotation_euler = (Vector(look) - Vector(pos)).to_track_quat('-Z', 'Y').to_euler()
        sc.render.filepath = preview.replace('.png', f'_{i}.png')
        bpy.ops.render.render(write_still=True)
