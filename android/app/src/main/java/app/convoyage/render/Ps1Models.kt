package app.convoyage.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Per-vehicle framing numbers for the camera and the ground. */
class Ps1Frame(
    /** Radius of a sphere that holds the vehicle; the camera backs off to fit it. */
    val fitRadius: Float,
    /** Height of the ground disc (the wheels' lowest point). */
    val groundY: Float,
    val groundRadius: Float,
    /** Height the camera looks at. */
    val lookY: Float,
)

/**
 * One cross-section of a lofted body at [x] (forward is +x). [sill] is the bottom of the body side, [belt] the window
 * line (or the top of a bonnet or boot), [roof] the top of the glasshouse; roof == belt means no cabin here. [w] is the
 * half width. [pillar] paints the side of the piece that starts here (a B pillar, or a van's blind panel).
 */
class St(val x: Float, val sill: Float, val belt: Float, val roof: Float, val w: Float, val pillar: Boolean = false) {
    val cabin: Boolean get() = roof > belt + 0.08f
}

/**
 * The vehicles, built in code in the PS1 manner: low-poly lofted bodies, boxes and 8-sided wheels. x is forward (the
 * lights at +x), y is up and z is across (+z is the right-hand side). Four-wheelers are built from a [Ps1Shape] in
 * metres at [K] units per metre, centred on their length, with the wheel centres at y = 0.
 */
object Ps1Models {
    /** Model units per metre. */
    const val K = 1.5f

    /** Body height change per millimetre of ride height. */
    private const val MM = 0.0016f

    private val TYRE = Surf.srgb(0x16181C)
    private val GLASS = Surf.srgb(0x0E1A28, flag = 3)
    private val DARK = Surf.srgb(0x0A0D12)
    private val ARCH = Surf.srgb(0x050608)
    private val TRIM = Surf.srgb(0x3A414C)
    private val CHROME = Surf.srgb(0xB8BCC2)
    private val ALLOY = Surf.srgb(0x8E959F)
    private val PLATE = Surf.srgb(0xE8EAEE)
    private val SEAT = Surf.srgb(0x15171C)
    private val HEAD = Surf.srgb(0xDFF9FF, flag = 2)
    private val TAIL = Surf.srgb(0xFF3D5A, flag = 2)
    private val GROUND = Surf.srgb(0xFFFFFF, tex = Ps1Textures.GROUND)
    private val SHADOW = Surf.srgb(0x05080D)
    private val FOG = Surf.srgb(0xFFD27A, flag = 2)
    private val ROTOR = Surf.srgb(0x5A5F66)
    private val RED = Surf.srgb(0xB0141E)

    fun frame(spec: Ps1Spec): Ps1Frame = spec.shape?.let { frame(it) } ?: bikeFrame(spec.body)

    fun frame(body: Ps1Body): Ps1Frame = Ps1Shapes.generic(body)?.let { frame(it) } ?: bikeFrame(body)

    fun frame(sh: Ps1Shape): Ps1Frame {
        val fit = hypot(sh.len / 2f, sh.hgt / 2f) * K * 1.02f
        return Ps1Frame(fitRadius = fit, groundY = -sh.tyre / 2f * K, groundRadius = fit * 1.3f, lookY = (sh.hgt * 0.42f - sh.tyre / 2f) * K)
    }

    private fun bikeFrame(body: Ps1Body): Ps1Frame {
        val r = bikeSpec(body).wheelR
        val big = body == Ps1Body.Cruiser || body == Ps1Body.Tourer
        return Ps1Frame(fitRadius = if (big) 2.9f else 2.7f, groundY = -r, groundRadius = 3.6f, lookY = 0.45f)
    }

    /** Wheels and size of a motorcycle: where the axles are, how big the wheels. */
    private class Spec(val wheelR: Float, val axles: FloatArray, val track: Float, val wheelW: Float)

    private fun bikeSpec(body: Ps1Body): Spec = when (body) {
        Ps1Body.Cruiser -> Spec(0.6f, floatArrayOf(-1.75f, 1.9f), 0f, 0.3f)
        Ps1Body.Tourer -> Spec(0.62f, floatArrayOf(-1.75f, 1.85f), 0f, 0.3f)
        Ps1Body.Adventure -> Spec(0.7f, floatArrayOf(-1.55f, 1.65f), 0f, 0.22f)
        Ps1Body.Dirt -> Spec(0.72f, floatArrayOf(-1.5f, 1.6f), 0f, 0.18f)
        Ps1Body.Scooter -> Spec(0.42f, floatArrayOf(-1.3f, 1.3f), 0f, 0.26f)
        else -> Spec(0.62f, floatArrayOf(-1.5f, 1.55f), 0f, 0.24f)
    }

    fun build(spec: Ps1Spec): Ps1Mesh {
        val mb = MeshBuilder()
        val f = frame(spec)
        Ps1Geo.disc(mb, f.groundY, f.groundRadius, f.groundRadius, 12, GROUND, uvScale = 6f)
        val groundIndices = mb.indexCount

        val paint = Surf.srgb(spec.paint, Ps1Textures.BODY, 1)
        val rim = Surf.srgb(spec.wheel.rgb, Ps1Textures.RIM)
        val dy = spec.rideHeightMm * MM
        val sh = spec.shape
        val modelled = spec.model?.let { Ps1CarModels.of(it.id) }
        when {
            sh == null -> bike(mb, spec.body, bikeSpec(spec.body), paint, rim, dy)
            // Modelled bodies are smooth clear-coated paint, no texture.
            modelled != null -> modelledCar(mb, modelled, Surf.srgb(spec.paint, 0, 1), rim, dy)
            else -> Car(mb, sh, paint, rim, dy).build()
        }

        // The shadow blob under the vehicle, a hair above the ground.
        if (sh == null) Ps1Geo.disc(mb, f.groundY + 0.012f, 2.5f, 0.75f, 10, SHADOW)
        else Ps1Geo.disc(mb, f.groundY + 0.012f, sh.len / 2f * K * 1.02f, sh.wid / 2f * K * 1.12f, 10, SHADOW)
        return mb.build(groundIndices)
    }

    // ---- four wheels ------------------------------------------------------------------------------------------

    /** A body modelled in Blender: its parts take the paint, glass and lamp surfaces; the wheels go in its arches. */
    private fun modelledCar(mb: MeshBuilder, cm: Ps1CarModel, paint: Surf, rim: Surf, dyIn: Float) {
        val h0 = cm.tyre / 2f
        val dy = maxOf(dyIn, -0.06f * K)
        for (part in cm.parts) {
            val s = when (part.material) {
                "paint" -> paint; "glass" -> GLASS; "dark" -> ARCH; "black" -> DARK; "trim" -> TRIM; "chrome" -> CHROME
                "head" -> HEAD; "tail" -> TAIL; "fog" -> FOG; "plate" -> PLATE; "red" -> RED; else -> paint
            }
            val pos = part.positions; val nrm = part.normals
            val ids = IntArray(pos.size / 3) { i ->
                val x = pos[i * 3] * K; val y = (pos[i * 3 + 1] - h0) * K + dy; val z = pos[i * 3 + 2] * K
                mb.vertex(x, y, z, nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2], x / 7f + 0.5f, y / 2f, s)
            }
            val ix = part.indices
            for (t in 0 until ix.size / 3) mb.tri(ids[ix[t * 3]], ids[ix[t * 3 + 1]], ids[ix[t * 3 + 2]])
        }
        val r = cm.tyre / 2f * K
        val ww = cm.tyreW * K
        for ((i, d) in floatArrayOf(cm.fo, cm.fo + cm.wb).withIndex()) {
            val ax = (cm.len / 2f - d) * K
            val ht = cm.halfTrack[i] * K
            for (side in intArrayOf(1, -1)) {
                Ps1Geo.wheel(mb, ax, 0f, side * ht, r, ww, TYRE, rim, segments = 16)
                // Brake disc and caliper behind the spokes.
                Ps1Geo.wheel(mb, ax, 0f, side * (ht - ww * 0.3f), r * 0.62f, 0.05f, ROTOR, ROTOR, segments = 12)
                Ps1Geo.box(mb, ax - r * 0.42f, r * 0.2f, side * (ht - ww * 0.22f), r * 0.22f, r * 0.42f, 0.06f, TAIL)
            }
        }
    }

    /** Builds one four-wheeler from its shape and profile. Helpers take metres (back from the nose, up from the ground). */
    private class Car(val mb: MeshBuilder, val sh: Ps1Shape, val paint: Surf, val rim: Surf, dyIn: Float) {
        /** Lowering stops just short of the sill touching the ground. */
        val dy = maxOf(dyIn, -(sh.sill - 0.06f) * K)
        val t = sh.tpl
        val p = Ps1Profiles.of(sh)
        val L = sh.len
        val r = sh.tyre / 2f * K
        /** Back of the body shell: the cab on a ute, else the tail. */
        val end = p.end
        val ute = end < L - 0.3f
        val kit = sh.kit

        fun x(d: Float) = (L / 2f - d) * K
        fun y(h: Float) = (h - sh.tyre / 2f) * K + dy
        fun m(v: Float) = v * K
        fun half(d: Float) = p.wAt(d) * K

        /** Which part of the glasshouse a stretch of body is: 0 body, 1 windscreen, 2 roof, 3 rear glass. */
        fun zone(d: Float) = when {
            !p.hasCabin -> 0
            d < p.cowl -> 0
            d < p.roofF -> 1
            d < p.roofR -> 2
            d < p.glassEnd -> 3
            else -> 0
        }

        /** The cross-section at [d]: (y, z) from the rocker on the +z side up to the crown; the -z side mirrors it. */
        fun ring(d: Float): List<Pair<Float, Float>> {
            val top = p.topAt(d); val bot = p.botAt(d); val w = half(d)
            val cabin = zone(d) != 0 || (d == p.glassEnd && p.hasCabin)
            val belt = if (cabin) minOf(p.beltAt(d), top - 0.01f) else top - 0.06f
            val shoulder = minOf(belt - 0.07f, bot + (belt - bot) * 0.7f)
            val mid = bot + (shoulder - bot) * 0.45f
            val pts = ArrayList<Pair<Float, Float>>(8)
            pts += Pair(bot, w * 0.88f)
            pts += Pair(minOf(bot + 0.07f, mid), w * 0.98f)
            pts += Pair(mid, w)
            pts += Pair(shoulder, w * 0.985f)
            pts += Pair(belt, w * 0.95f)
            if (cabin) {
                // Side glass leans in a little (it reads small from above if it leans in a lot); the roof edge rounds over.
                val tw = w * minOf(0.97f, p.tumble + 0.1f)
                pts += Pair(maxOf(top - 0.07f, belt + 0.005f), tw)
                pts += Pair(maxOf(top - 0.02f, belt + 0.01f), tw * 0.88f)
            } else {
                pts += Pair(top - 0.025f, w * 0.82f)
                pts += Pair(top - 0.008f, w * 0.5f)
            }
            pts += Pair(top, 0f)
            return pts.map { Pair(y(it.first), it.second) }
        }

        /** Where to cut the body: every curve point and marker, then no gap wider than 30 cm. */
        fun stations(): List<Float> {
            val keys = sortedSetOf<Float>()
            for (list in listOf(p.top, p.bot, p.plan, p.belt)) for (q in list) if (q.d <= end) keys += q.d
            keys += listOf(0f, end, p.cowl, p.roofF, p.roofR, p.glassEnd, sh.fo, sh.fo + sh.wb)
            for (w in p.windows) { keys += w.start; keys += w.endInclusive }
            val base = keys.filter { it in 0f..end }
            val out = ArrayList<Float>()
            for (i in base.indices) {
                if (i > 0) {
                    val a = base[i - 1]; val b = base[i]
                    val n = ((b - a) / 0.3f).toInt()
                    for (k in 1..n) out += a + (b - a) * k / (n + 1)
                }
                out += base[i]
            }
            val res = ArrayList<Float>()
            for (d in out) if (res.isEmpty() || d - res.last() > 0.005f) res += d
            return res
        }

        fun body() {
            val ds = stations()
            fun uv(xx: Float, yy: Float) = floatArrayOf(xx / 7f + 0.5f, yy / 2f)
            val rings = ds.map { ring(it) }
            for (i in 0 until ds.size - 1) {
                val da = ds[i]; val db = ds[i + 1]
                val dm = (da + db) / 2f
                val zn = zone(dm)
                // Side glass wherever a window spans, under the A pillar and into the C pillar too.
                val window = zn != 0 && p.windows.any { dm in it }
                val ra = rings[i]; val rb = rings[i + 1]
                val xa = x(da); val xb = x(db)
                val yc = (ra[0].first + ra.last().first) / 2f
                for (k in 0 until ra.size - 1) {
                    val s = when (k) {
                        4 -> if (window) GLASS else paint
                        // The edge strip is the A/C pillar or the drip rail; the middle is the windscreen or rear glass.
                        5 -> paint
                        6 -> if (zn == 1 || zn == 3) GLASS else paint
                        else -> paint
                    }
                    for (side in floatArrayOf(1f, -1f)) {
                        val p0 = floatArrayOf(xa, ra[k].first, ra[k].second * side)
                        val p1 = floatArrayOf(xa, ra[k + 1].first, ra[k + 1].second * side)
                        val q0 = floatArrayOf(xb, rb[k].first, rb[k].second * side)
                        val q1 = floatArrayOf(xb, rb[k + 1].first, rb[k + 1].second * side)
                        val hy = (ra[k].first + ra[k + 1].first) / 2f - yc
                        val hz = (ra[k].second + ra[k + 1].second) / 2f * side
                        Ps1Geo.flatTri(mb, s, p0, q0, q1, uv(p0[0], p0[1]), uv(q0[0], q0[1]), uv(q1[0], q1[1]), 0f, hy, hz)
                        Ps1Geo.flatTri(mb, s, p0, q1, p1, uv(p0[0], p0[1]), uv(q1[0], q1[1]), uv(p1[0], p1[1]), 0f, hy, hz)
                    }
                }
                // Underside.
                val a0 = floatArrayOf(xa, ra[0].first, ra[0].second); val a1 = floatArrayOf(xa, ra[0].first, -ra[0].second)
                val b0 = floatArrayOf(xb, rb[0].first, rb[0].second); val b1 = floatArrayOf(xb, rb[0].first, -rb[0].second)
                val z = floatArrayOf(0f, 0f)
                Ps1Geo.flatTri(mb, DARK, a0, b0, b1, z, z, z, 0f, -1f, 0f)
                Ps1Geo.flatTri(mb, DARK, a0, b1, a1, z, z, z, 0f, -1f, 0f)
            }
            // Ends: fans from the middle of the first and last rings. A cab's back carries its rear window.
            for ((ring, dir, xe) in listOf(Triple(rings.first(), 1f, x(ds.first())), Triple(rings.last(), -1f, x(ds.last())))) {
                val full = ring + ring.reversed().map { Pair(it.first, -it.second) }
                val cy = (ring.first().first + ring.last().first) / 2f
                val c = floatArrayOf(xe, cy, 0f)
                for (k in 0 until full.size - 1) {
                    val a = floatArrayOf(xe, full[k].first, full[k].second)
                    val b = floatArrayOf(xe, full[k + 1].first, full[k + 1].second)
                    val glass = dir < 0 && ute && p.hasCabin && minOf(full[k].first, full[k + 1].first) > y(p.beltAt(end) + 0.04f)
                    Ps1Geo.flatTri(mb, if (glass) GLASS else paint, c, a, b, uvOf(c), uvOf(a), uvOf(b), dir, 0f, 0f)
                }
            }
        }

        fun uvOf(v: FloatArray) = floatArrayOf(v[0] / 7f + 0.5f, v[1] / 2f)

        fun build() {
            body()
            val fx = x(sh.fo)
            val axles = floatArrayOf(fx - m(sh.wb), fx)
            val ww = m(sh.tyreW) * 1.15f
            for ((i, ax) in axles.withIndex()) {
                val d = if (i == 1) sh.fo else sh.fo + sh.wb
                val hw = half(d)
                val underDeck = i == 0 && t.flatDeck
                val wz = hw - ww / 2f + 0.05f
                for (side in intArrayOf(1, -1)) {
                    if (Ps1Kit.Flares in kit && !underDeck) arch(mb, ax, 0f, side * (hw + 0.01f), r * 1.36f, side, DARK)
                    if (!underDeck) arch(mb, ax, 0f, side * (hw + 0.018f), r * 1.18f, side, ARCH)
                    Ps1Geo.wheel(mb, ax, 0f, side * wz, r, ww, TYRE, rim, segments = 12)
                }
            }
            if (ute) if (t.flatDeck) flatDeck() else tub()
            face()
            if (!ute) tail()
            mirrors()
            if (!p.hasCabin) cockpit()
            kits(axles)
        }

        // ---- the ends ----

        fun box(d: Float, h: Float, z: Float, sx: Float, sy: Float, sz: Float, s: Surf, rotZ: Float = 0f) =
            Ps1Geo.box(mb, x(d), y(h), z, sx, sy, sz, s, rotZ)

        /** How far back from the nose the body reaches height [h] (where a lamp at that height sits). */
        fun frontAt(h: Float): Float {
            var d = 0f
            while (d < 1.2f && (p.topAt(d) < h || p.botAt(d) > h)) d += 0.02f
            return d
        }

        /** How far back the tail reaches height [h]. */
        fun rearAt(h: Float): Float {
            var d = end
            while (d > end - 1.2f && (p.topAt(d) < h || p.botAt(d) > h)) d -= 0.02f
            return d
        }

        fun lamp(h: Float, z: Float, radius: Float) =
            Ps1Geo.wheel(mb, x(frontAt(h)) + 0.03f, y(h), z, radius, 0.06f, HEAD, HEAD, segments = 8, rotY = (PI / 2).toFloat())

        /** A flat feature on the nose at height [h], [tall] and [wide] in metres, pushed onto the body surface. */
        fun front(h: Float, z: Float, tall: Float, wide: Float, s: Surf, out: Float = 0f) {
            val d = minOf(frontAt(h + tall / 2f), frontAt(h - tall / 2f).coerceAtLeast(0f))
            box(d + 0.02f - out, h, z, 0.08f, m(tall), m(wide), s)
        }

        fun face() {
            val n = p.topAt(0.3f); val s = p.botAt(0.3f)
            val mid = (s + n) / 2f
            val hw = p.wAt(0.15f)
            fun lights(h: Float, tall: Float, wide: Float, at: Float = 0.66f) {
                for (z in floatArrayOf(hw * at, -hw * at)) front(h, m(z), tall, wide, HEAD)
            }
            when (sh.face) {
                Ps1Face.Generic -> { lights(n - 0.13f, 0.1f, hw * 0.42f); front(mid + 0.02f, 0f, 0.15f, hw * 0.9f, DARK) }
                Ps1Face.Kidney, Ps1Face.KidneyTall -> {
                    lights(n - 0.12f, 0.08f, hw * 0.42f, 0.68f)
                    val tall = if (sh.face == Ps1Face.KidneyTall) 0.34f else 0.13f
                    val h = n - 0.06f - tall / 2f
                    for (z in floatArrayOf(0.13f, -0.13f)) {
                        front(h, m(z), tall + 0.03f, 0.25f, CHROME)
                        front(h, m(z), tall, 0.21f, DARK, out = 0.01f)
                    }
                    front(s + 0.15f, 0f, 0.1f, hw * 1.1f, DARK)
                }
                Ps1Face.Wide -> { lights(n - 0.1f, 0.075f, hw * 0.45f, 0.68f); front(mid - 0.03f, 0f, 0.28f, hw * 1.25f, DARK) }
                Ps1Face.Singleframe -> { lights(n - 0.1f, 0.075f, hw * 0.42f, 0.7f); front(mid, 0f, 0.36f, hw * 0.95f, DARK) }
                Ps1Face.SevenSlot -> {
                    for (i in -3..3) front(n - 0.2f, m(0.1f * i), 0.3f, 0.05f, DARK)
                    for (z in floatArrayOf(hw * 0.74f, -hw * 0.74f)) lamp(n - 0.2f, m(z), m(0.09f))
                    front(s + 0.15f, 0f, 0.12f, hw * 1.2f, TRIM)
                }
                Ps1Face.Round -> {
                    for (z in floatArrayOf(hw * 0.66f, -hw * 0.66f)) lamp(n - 0.15f, m(z), m(0.09f))
                    front(mid, 0f, 0.14f, hw * 0.7f, DARK)
                }
                Ps1Face.CClamp -> {
                    front(n - 0.22f, 0f, 0.3f, hw * 1.2f, DARK)
                    for (sz in floatArrayOf(1f, -1f)) {
                        front(n - 0.2f, m(sz * hw * 0.86f), 0.28f, 0.05f, HEAD, out = 0.01f)
                        front(n - 0.07f, m(sz * hw * 0.78f), 0.05f, 0.18f, HEAD, out = 0.01f)
                        front(n - 0.33f, m(sz * hw * 0.78f), 0.05f, 0.18f, HEAD, out = 0.01f)
                    }
                }
                Ps1Face.LightBar -> {
                    front(n - 0.06f, 0f, 0.035f, hw * 1.9f, HEAD)
                    lights(n - 0.11f, 0.06f, hw * 0.36f, 0.7f)
                    front(mid - 0.04f, 0f, 0.2f, hw * 1.1f, DARK)
                }
                Ps1Face.Slim -> { lights(n - 0.09f, 0.055f, hw * 0.4f, 0.68f); front(s + 0.17f, 0f, 0.12f, hw * 1.2f, DARK) }
            }
            front(s + 0.2f, 0f, 0.11f, 0.37f, PLATE, out = 0.02f)
        }

        fun tail() {
            val deck = p.topAt(end - 0.15f)
            val upright = p.hasCabin && end - p.glassEnd < 0.2f
            val h = if (upright) p.beltAt(end) - 0.05f else deck - 0.08f
            val hw = p.wAt(end - 0.15f)
            fun back(hh: Float, z: Float, tall: Float, wide: Float, s: Surf) {
                val d = maxOf(rearAt(hh + tall / 2f), rearAt(hh - tall / 2f))
                box(d - 0.02f, hh, z, 0.08f, m(tall), m(wide), s)
            }
            when {
                Ps1Kit.TriTail in kit -> for (sz in floatArrayOf(1f, -1f)) for (k in 0..2) back(h, m(sz * hw * (0.55f + 0.1f * k)), 0.15f, 0.045f, TAIL)
                upright -> for (z in floatArrayOf(hw * 0.82f, -hw * 0.82f)) back(h, m(z), 0.22f, hw * 0.28f, TAIL)
                else -> for (z in floatArrayOf(hw * 0.7f, -hw * 0.7f)) back(h, m(z), 0.09f, hw * 0.42f, TAIL)
            }
            if (Ps1Kit.RearBar in kit) back(h + 0.05f, 0f, 0.035f, hw * 1.8f, TAIL)
            back(p.botAt(end) + 0.15f, 0f, 0.11f, 0.37f, PLATE)
        }

        fun mirrors() {
            val dm = p.cowl + 0.2f
            val hw = p.wAt(dm)
            for (side in intArrayOf(1, -1)) box(dm, p.beltAt(dm) + 0.06f, side * m(hw + 0.07f), 0.16f, 0.11f, 0.16f, paint)
        }

        /** A ute tub from the back of the cab to the tail: floor, liner, sides and tailgate. */
        fun tub() {
            val x0 = x(end) - 0.04f; val x1 = x(L)
            val len = x0 - x1; val cx = (x0 + x1) / 2f
            val hw = half(L - 0.5f)
            val floorY = y(sh.sill + 0.25f); val topY = y(sh.deck)
            Ps1Geo.box(mb, cx, floorY - 0.18f, 0f, len, 0.36f, hw * 1.9f, TRIM)
            Ps1Geo.box(mb, cx, floorY + 0.02f, 0f, len, 0.06f, hw * 1.86f, DARK)
            val wallH = topY - floorY + 0.36f
            for (z in floatArrayOf(hw - 0.06f, -(hw - 0.06f))) Ps1Geo.box(mb, cx, topY - wallH / 2f, z, len, wallH, 0.12f, paint)
            Ps1Geo.box(mb, x1 + 0.06f, topY - wallH / 2f, 0f, 0.12f, wallH, hw * 2f, paint)
            for (z in floatArrayOf(hw * 0.82f, -hw * 0.82f)) Ps1Geo.box(mb, x1 - 0.01f, topY - 0.15f, z, 0.06f, m(0.2f), hw * 0.25f, TAIL)
            Ps1Geo.box(mb, x1 - 0.02f, floorY - 0.1f, 0f, 0.04f, m(0.11f), m(0.37f), PLATE)
            if (Ps1Kit.Canopy in kit) {
                val ch = y(p.topAt(p.roofR) - 0.03f) - topY
                Ps1Geo.box(mb, cx, topY + ch / 2f, 0f, len, ch, hw * 2f, paint)
                for (z in floatArrayOf(hw + 0.005f, -hw - 0.005f)) Ps1Geo.box(mb, cx, topY + ch * 0.55f, z, len * 0.8f, ch * 0.55f, 0.01f, GLASS)
            }
        }

        /** A cab-chassis: rails, an alloy tray above the wheels, drop sides and a headboard. */
        fun flatDeck() {
            val x0 = x(end) - 0.06f; val x1 = x(L)
            val len = x0 - x1; val cx = (x0 + x1) / 2f
            val hw = half(end) * 1.02f
            for (z in floatArrayOf(m(0.42f), -m(0.42f))) Ps1Geo.box(mb, (x(sh.fo) + x1) / 2f, y(sh.sill + 0.12f), z, x(sh.fo) - x1, m(0.18f), m(0.08f), DARK)
            val deckY = y(sh.tyre + 0.1f)
            Ps1Geo.box(mb, cx, deckY, 0f, len, m(0.1f), hw * 2.06f, ALLOY)
            val sideH = m(0.3f)
            for (z in floatArrayOf(hw * 1.02f, -hw * 1.02f)) Ps1Geo.box(mb, cx, deckY + sideH / 2f, z, len, sideH, 0.06f, ALLOY)
            Ps1Geo.box(mb, x1 + 0.03f, deckY + sideH / 2f, 0f, 0.06f, sideH, hw * 2.06f, ALLOY)
            val headTop = y(sh.hgt - 0.12f)
            Ps1Geo.box(mb, x0 - 0.04f, (deckY + headTop) / 2f, 0f, 0.08f, headTop - deckY, hw * 2.02f, TRIM)
            for (z in floatArrayOf(hw * 0.8f, -hw * 0.8f)) Ps1Geo.box(mb, x1 + 0.02f, deckY - m(0.12f), z, 0.06f, m(0.12f), hw * 0.28f, TAIL)
            Ps1Geo.box(mb, x1 + 0.02f, deckY - m(0.25f), 0f, 0.04f, m(0.11f), m(0.37f), PLATE)
        }

        /** Open cars: the cockpit, seats with hoops, a raked windscreen frame and, on convertibles, the folded hood. */
        fun cockpit() {
            val ws = p.cowl
            val seatD = ws + (L - ws) * 0.42f
            val bt = p.topAt(seatD)
            val hw = half(seatD)
            box((ws + seatD + 0.4f) / 2f, bt - 0.01f, 0f, m(seatD + 0.4f - ws - 0.1f), 0.04f, hw * 1.55f, DARK)
            for (z in floatArrayOf(m(0.3f), -m(0.3f))) {
                box(seatD, bt + 0.15f, z, m(0.17f), m(0.33f), m(0.37f), SEAT)
                box(seatD + 0.2f, bt + 0.25f, z, 0.08f, m(0.4f), 0.08f, CHROME)
            }
            Ps1Geo.box(mb, x(ws + 0.1f), y(p.topAt(ws) + 0.17f), 0f, 0.05f, m(0.34f), half(ws) * 1.75f, GLASS, rotZ = 0.55f)
            if (sh.body == Ps1Body.Convertible) box(seatD + 0.55f, bt + 0.05f, 0f, m(0.5f), m(0.1f), hw * 1.6f, DARK)
        }

        // ---- kit ----

        fun kits(axles: FloatArray) {
            val rf = p.roofF; val rr = p.roofR
            val roofW = half((rf + rr) / 2f) * p.tumble
            val deck = p.topAt(end - 0.2f)
            val hatchback = p.hasCabin && end - p.glassEnd < 0.35f
            for (k in kit) when (k) {
                Ps1Kit.RoofRails -> if (p.hasCabin) for (z in floatArrayOf(roofW * 0.78f, -roofW * 0.78f))
                    box((rf + rr) / 2f, p.topAt((rf + rr) / 2f) + 0.02f, z, m(rr - rf - 0.2f), 0.06f, 0.08f, DARK)
                Ps1Kit.RoofRack -> if (p.hasCabin) {
                    val h = p.topAt((rf + rr) / 2f) + 0.07f
                    box((rf + rr) / 2f + 0.1f, h, 0f, m(rr - rf - 0.35f), 0.06f, roofW * 1.9f, DARK)
                    for (z in floatArrayOf(roofW * 0.95f, -roofW * 0.95f)) box((rf + rr) / 2f + 0.1f, h + 0.04f, z, m(rr - rf - 0.35f), 0.08f, 0.05f, DARK)
                }
                Ps1Kit.RoofSpoiler -> if (p.hasCabin) Ps1Geo.box(mb, x(rr) - 0.1f, y(p.topAt(rr)) - 0.03f, 0f, 0.3f, 0.04f, half(rr) * 1.7f, paint, rotZ = 0.12f)
                Ps1Kit.Wing -> {
                    val wd = end - 0.25f
                    val base = p.topAt(wd)
                    for (z in floatArrayOf(half(wd) * 0.6f, -half(wd) * 0.6f)) box(wd, base + 0.12f, z, 0.1f, m(0.24f), 0.06f, DARK)
                    box(wd, base + 0.25f, 0f, m(0.3f), 0.05f, half(wd) * 1.85f, paint)
                }
                Ps1Kit.BootLip -> if (!hatchback) box(end - 0.1f, deck + 0.02f, 0f, 0.18f, 0.05f, half(end - 0.1f) * 1.6f, paint)
                Ps1Kit.Ducktail -> box(end - 0.18f, deck + 0.03f, 0f, 0.32f, 0.08f, half(end - 0.18f) * 1.7f, paint, rotZ = -0.25f)
                Ps1Kit.Scoop -> {
                    val sd = p.cowl * 0.5f; val h = p.topAt(sd) + 0.04f
                    box(sd, h, 0f, m(0.36f), 0.1f, m(0.4f), paint)
                    box(sd - 0.17f, h, 0f, 0.03f, 0.07f, m(0.34f), DARK)
                }
                Ps1Kit.Bulge -> box(p.cowl * 0.5f, p.topAt(p.cowl * 0.5f) + 0.02f, 0f, m(p.cowl * 0.8f), 0.08f, half(p.cowl * 0.5f) * 0.6f, paint)
                Ps1Kit.Stripes -> stripes()
                Ps1Kit.RooBar -> {
                    val b = p.botAt(0f)
                    box(-0.08f, b + 0.12f, 0f, 0.14f, m(0.36f), half(0.2f) * 1.95f, DARK)
                    for (z in floatArrayOf(m(0.3f), -m(0.3f))) box(-0.1f, p.topAt(0.3f) - 0.05f, z, 0.08f, m(0.36f), 0.08f, DARK)
                    box(-0.1f, p.topAt(0.3f) + 0.1f, 0f, 0.08f, 0.08f, m(0.68f), DARK)
                }
                Ps1Kit.Snorkel -> {
                    val sd = p.cowl + 0.12f
                    val top = (if (p.hasCabin) p.topAt(sd) else p.topAt(p.cowl)) + 0.05f
                    val b = p.beltAt(sd) - 0.35f
                    val z = -(half(sd) + m(0.06f))
                    box(sd, (b + top) / 2f, z, 0.14f, m(top - b), 0.14f, DARK)
                    box(sd - 0.08f, top, z, m(0.22f), 0.14f, 0.16f, DARK)
                }
                Ps1Kit.Spare -> Ps1Geo.wheel(mb, x(end) - 0.17f, y(p.beltAt(end) - 0.12f), 0f, r * 0.95f, m(sh.tyreW), TYRE, rim, segments = 12, rotY = (PI / 2).toFloat())
                Ps1Kit.SportsBar -> if (ute && !t.flatDeck) {
                    val bd = end + 0.3f; val top = p.topAt(p.roofR) - 0.06f
                    val hw = half(L - 0.5f)
                    for (z in floatArrayOf(hw * 0.85f, -hw * 0.85f)) box(bd, (sh.deck + top) / 2f, z, 0.1f, m(top - sh.deck), 0.1f, DARK)
                    box(bd, top, 0f, 0.1f, 0.1f, hw * 1.8f, DARK)
                }
                Ps1Kit.Steps -> {
                    val len = m(sh.wb - sh.tyre - 0.1f)
                    val hw = half(sh.fo + sh.wb / 2f)
                    for (side in intArrayOf(1, -1)) Ps1Geo.box(mb, (axles[0] + axles[1]) / 2f, y(p.botAt(sh.fo + sh.wb / 2f) - 0.03f), side * (hw + 0.06f), len, 0.05f, 0.18f, DARK)
                }
                else -> Unit
            }
        }

        /** Twin stripes over every painted top surface, nose to tail, skipping the glass. */
        fun stripes() {
            val ds = stations()
            for (i in 0 until ds.size - 1) {
                val zn = zone((ds[i] + ds[i + 1]) / 2f)
                if (zn == 1 || zn == 3) continue
                val ya = y(p.topAt(ds[i])) + 0.012f; val yb = y(p.topAt(ds[i + 1])) + 0.012f
                val xa = x(ds[i]); val xb = x(ds[i + 1])
                for ((z0, z1) in listOf(0.06f to 0.26f, -0.26f to -0.06f)) {
                    val uv = floatArrayOf(0.5f, 0.5f)
                    Ps1Geo.flatTri(mb, PLATE, floatArrayOf(xa, ya, z0), floatArrayOf(xb, yb, z0), floatArrayOf(xb, yb, z1), uv, uv, uv, 0f, 1f, 0f)
                    Ps1Geo.flatTri(mb, PLATE, floatArrayOf(xa, ya, z0), floatArrayOf(xb, yb, z1), floatArrayOf(xa, ya, z1), uv, uv, uv, 0f, 1f, 0f)
                }
            }
        }
    }

    /** The dark wheel-arch opening on a body side: a fan over the top of the wheel, facing out along [side] z. */
    private fun arch(mb: MeshBuilder, cx: Float, cy: Float, z: Float, r: Float, side: Int, s: Surf = ARCH) {
        val n = 8
        val nz = side.toFloat()
        val centre = mb.vertex(cx, cy - r * 0.15f, z, 0f, 0f, nz, 0.5f, 0.5f, s)
        val ring = IntArray(n + 1) { i ->
            val a = (PI * (-0.08 + 1.16 * i / n)).toFloat()
            mb.vertex(cx + r * cos(a), cy + r * sin(a), z, 0f, 0f, nz, 0f, 0f, s)
        }
        for (i in 0 until n) if (side > 0) mb.tri(centre, ring[i], ring[i + 1]) else mb.tri(centre, ring[i + 1], ring[i])
    }

    // ---- two wheels --------------------------------------------------------------------------------------------

    // Motorcycle shapes in model units (px coordinates 75 + 20x, 48 - 20y for Ps1Geo.extrude).
    private fun u(vararg v: Float): List<Pair<Float, Float>> = List(v.size / 2) { Pair(75f + v[it * 2] * 20f, 48f - v[it * 2 + 1] * 20f) }

    private fun bike(mb: MeshBuilder, body: Ps1Body, s: Spec, paint: Surf, rim: Surf, dy: Float) {
        val rearX = s.axles[0]; val frontX = s.axles[1]
        val seat = Surf.srgb(0x15171C)
        when (body) {
            Ps1Body.Naked -> {
                Ps1Geo.extrude(mb, u(-0.5f, 0.78f, -0.4f, 1.1f, 0.2f, 1.26f, 0.85f, 1.2f, 1.0f, 1.0f, 0.85f, 0.82f), 0.62f, 0.8f, paint, taperY = 0.95f, dy = dy)
                Ps1Geo.extrude(mb, u(-2.05f, 0.86f, -1.95f, 1.12f, -1.2f, 1.2f, -0.5f, 1.1f, -0.5f, 0.84f), 0.46f, 0.8f, paint, taperY = 1.0f, dy = dy)
                Ps1Geo.extrude(mb, u(-1.5f, 1.2f, -0.5f, 1.12f, -0.45f, 0.98f, -1.5f, 1.0f), 0.5f, 0.9f, seat, taperY = 5f, dy = dy)
                Ps1Geo.box(mb, 1.02f, 1.28f + dy, 0f, 0.1f, 0.1f, 1.2f, DARK)
                Ps1Geo.box(mb, 1.22f, 1.0f + dy, 0f, 0.14f, 0.28f, 0.28f, HEAD)
            }
            Ps1Body.SportBike -> {
                // Full fairing from the nose down round the engine, a tall tail, clip-ons under the screen.
                Ps1Geo.extrude(mb, u(-0.6f, 0.3f, -0.6f, 0.95f, -0.2f, 1.18f, 0.7f, 1.2f, 1.45f, 1.0f, 1.6f, 0.75f, 1.3f, 0.35f, 0.4f, 0.15f), 0.7f, 0.75f, paint, taperY = 0.9f, dy = dy)
                Ps1Geo.extrude(mb, u(1.05f, 1.2f, 1.45f, 1.0f, 1.25f, 1.42f), 0.45f, 0.8f, GLASS, taperY = 5f, dy = dy + 0.01f)
                Ps1Geo.extrude(mb, u(-2.15f, 1.05f, -2.0f, 1.32f, -1.1f, 1.25f, -0.6f, 1.0f, -0.6f, 0.85f), 0.44f, 0.8f, paint, taperY = 1.0f, dy = dy)
                Ps1Geo.extrude(mb, u(-1.4f, 1.24f, -0.55f, 1.14f, -0.5f, 1.02f, -1.4f, 1.06f), 0.42f, 0.9f, seat, taperY = 5f, dy = dy)
                Ps1Geo.box(mb, 1.58f, 0.82f + dy, 0f, 0.08f, 0.16f, 0.4f, HEAD)
            }
            Ps1Body.Cruiser -> {
                // Long and low: teardrop tank, low scooped seat, big fenders, raked forks, wide bars.
                Ps1Geo.extrude(mb, u(-0.6f, 0.95f, -0.4f, 1.2f, 0.4f, 1.28f, 1.0f, 1.15f, 0.9f, 0.95f), 0.66f, 0.8f, paint, taperY = 1.0f, dy = dy)
                Ps1Geo.extrude(mb, u(-1.5f, 0.88f, -0.6f, 0.95f, -0.6f, 0.82f, -1.4f, 0.75f), 0.62f, 0.9f, seat, taperY = 5f, dy = dy)
                Ps1Geo.extrude(mb, u(-2.4f, 0.35f, -2.3f, 0.75f, -1.75f, 0.92f, -1.2f, 0.8f, -1.6f, 0.68f, -2.2f, 0.4f), 0.5f, 0.9f, paint, taperY = 5f, dy = dy)
                Ps1Geo.extrude(mb, u(1.45f, 0.62f, 1.75f, 0.72f, 2.2f, 0.6f, 2.35f, 0.25f, 2.0f, 0.5f), 0.36f, 0.9f, paint, taperY = 5f, dy = dy)
                Ps1Geo.box(mb, 1.25f, 1.42f + dy, 0f, 0.1f, 0.1f, 1.5f, CHROME)
                Ps1Geo.box(mb, 1.55f, 1.05f + dy, 0f, 0.24f, 0.3f, 0.3f, HEAD)
                for (z in floatArrayOf(0.32f, -0.32f)) Ps1Geo.box(mb, -1.2f, 0.32f + dy, z, 2.2f, 0.16f, 0.14f, CHROME, rotZ = 0.04f)
            }
            Ps1Body.Adventure -> {
                // Tall: a big tank, a beak, a high screen, panniers on a rack.
                Ps1Geo.extrude(mb, u(-0.55f, 0.9f, -0.45f, 1.3f, 0.3f, 1.45f, 0.95f, 1.35f, 1.1f, 1.0f, 0.9f, 0.85f), 0.72f, 0.82f, paint, taperY = 1.1f, dy = dy)
                Ps1Geo.extrude(mb, u(1.1f, 1.05f, 1.7f, 0.95f, 1.15f, 0.85f), 0.3f, 0.9f, paint, taperY = 5f, dy = dy)
                Ps1Geo.extrude(mb, u(1.0f, 1.4f, 1.25f, 1.45f, 1.05f, 2.0f, 0.85f, 1.95f), 0.5f, 0.85f, GLASS, taperY = 5f, dy = dy + 0.01f)
                Ps1Geo.extrude(mb, u(-1.9f, 1.15f, -0.5f, 1.32f, -0.45f, 1.18f, -1.9f, 1.05f), 0.48f, 0.9f, seat, taperY = 5f, dy = dy)
                for (z in floatArrayOf(0.48f, -0.48f)) Ps1Geo.box(mb, -1.55f, 0.95f + dy, z, 0.9f, 0.62f, 0.3f, TRIM)
                Ps1Geo.box(mb, 0.98f, 1.5f + dy, 0f, 0.1f, 0.1f, 1.2f, DARK)
                Ps1Geo.box(mb, 1.25f, 1.12f + dy, 0f, 0.12f, 0.22f, 0.3f, HEAD)
            }
            Ps1Body.Tourer -> {
                // A full fairing with a tall screen, a long two-up seat, panniers and a top box.
                Ps1Geo.extrude(mb, u(-0.6f, 0.5f, -0.6f, 1.1f, 0.2f, 1.35f, 1.0f, 1.3f, 1.5f, 1.0f, 1.55f, 0.6f, 0.9f, 0.35f), 0.82f, 0.8f, paint, taperY = 1.0f, dy = dy)
                Ps1Geo.extrude(mb, u(1.0f, 1.32f, 1.35f, 1.05f, 1.15f, 1.85f, 0.95f, 1.8f), 0.55f, 0.85f, GLASS, taperY = 5f, dy = dy + 0.01f)
                Ps1Geo.extrude(mb, u(-1.6f, 1.25f, -0.55f, 1.2f, -0.5f, 1.05f, -1.6f, 1.08f), 0.55f, 0.9f, seat, taperY = 5f, dy = dy)
                for (z in floatArrayOf(0.5f, -0.5f)) Ps1Geo.box(mb, -1.55f, 0.85f + dy, z, 0.95f, 0.6f, 0.32f, paint)
                Ps1Geo.box(mb, -2.05f, 1.45f + dy, 0f, 0.6f, 0.45f, 0.6f, paint)
                Ps1Geo.box(mb, 1.55f, 1.0f + dy, 0f, 0.1f, 0.18f, 0.5f, HEAD)
            }
            Ps1Body.Dirt -> {
                // Tall and thin: shrouds, a long flat seat, high fenders and a number board.
                Ps1Geo.extrude(mb, u(-0.4f, 1.1f, -0.2f, 1.4f, 0.6f, 1.45f, 0.95f, 1.25f, 0.5f, 0.95f), 0.5f, 0.85f, paint, taperY = 1.2f, dy = dy)
                Ps1Geo.extrude(mb, u(-1.9f, 1.45f, -0.2f, 1.5f, 0.3f, 1.42f, -1.9f, 1.35f), 0.3f, 0.9f, seat, taperY = 5f, dy = dy)
                Ps1Geo.extrude(mb, u(-2.35f, 1.45f, -1.5f, 1.52f, -1.5f, 1.38f, -2.25f, 1.38f), 0.26f, 0.9f, paint, taperY = 5f, dy = dy)
                Ps1Geo.extrude(mb, u(1.2f, 1.0f, 1.7f, 1.06f, 1.95f, 0.96f, 1.3f, 0.92f), 0.2f, 0.9f, paint, taperY = 5f, dy = dy)
                Ps1Geo.box(mb, 1.12f, 1.25f + dy, 0f, 0.06f, 0.35f, 0.3f, PLATE)
                Ps1Geo.box(mb, 0.95f, 1.65f + dy, 0f, 0.08f, 0.08f, 1.3f, DARK)
            }
            Ps1Body.Scooter -> {
                // Step-through: a leg shield, a floorboard, and the body over the engine at the back.
                Ps1Geo.extrude(mb, u(0.55f, 0.25f, 0.7f, 1.2f, 1.0f, 1.28f, 1.1f, 0.45f), 0.5f, 0.85f, paint, taperY = 5f, dy = dy)
                Ps1Geo.box(mb, -0.15f, 0.35f + dy, 0f, 1.3f, 0.12f, 0.45f, paint)
                Ps1Geo.extrude(mb, u(-1.8f, 0.4f, -1.9f, 0.95f, -1.5f, 1.1f, -0.45f, 1.0f, -0.45f, 0.4f), 0.55f, 0.85f, paint, taperY = 0.9f, dy = dy)
                Ps1Geo.extrude(mb, u(-1.6f, 1.18f, -0.55f, 1.1f, -0.55f, 1.0f, -1.6f, 1.06f), 0.45f, 0.9f, seat, taperY = 5f, dy = dy)
                Ps1Geo.box(mb, 0.85f, 1.42f + dy, 0f, 0.08f, 0.08f, 0.8f, CHROME)
                Ps1Geo.box(mb, 1.02f, 1.18f + dy, 0f, 0.12f, 0.18f, 0.3f, HEAD)
            }
            else -> Unit
        }
        // Engine, swing arm and exhaust, forks, tail light, then the wheels.
        if (body != Ps1Body.Scooter) Ps1Geo.box(mb, 0.1f, 0.5f + dy, 0f, 1.2f, 0.7f, 0.56f, TRIM)
        for (z in floatArrayOf(0.25f, -0.25f)) Ps1Geo.box(mb, (rearX + 0.1f) / 2f, 0.3f + dy * 0.5f, z, -rearX + 0.2f, 0.1f, 0.08f, TRIM, rotZ = 0.12f)
        if (body != Ps1Body.Cruiser && body != Ps1Body.Scooter) Ps1Geo.box(mb, -0.9f, if (body == Ps1Body.Dirt) 0.75f + dy else 0.3f + dy, 0.34f, 1.7f, 0.15f, 0.14f, CHROME, rotZ = if (body == Ps1Body.SportBike) 0.35f else 0.08f)
        val rake = if (body == Ps1Body.Cruiser) 0.62f else 0.4f
        val forkLen = if (body == Ps1Body.Adventure || body == Ps1Body.Dirt) 1.5f else if (body == Ps1Body.Scooter) 1.0f else 1.3f
        for (z in floatArrayOf(0.2f, -0.2f)) Ps1Geo.box(mb, frontX - 0.25f, 0.6f + dy * 0.5f, z, 0.1f, forkLen, 0.09f, CHROME, rotZ = rake)
        Ps1Geo.box(mb, rearX - 0.55f, 1.0f + dy, 0f, 0.1f, 0.14f, 0.3f, TAIL)
        Ps1Geo.wheel(mb, rearX, 0f, 0f, s.wheelR, s.wheelW * (if (body == Ps1Body.Cruiser) 1.3f else 1f), TYRE, rim)
        Ps1Geo.wheel(mb, frontX, 0f, 0f, s.wheelR, s.wheelW, TYRE, rim)
    }
}
