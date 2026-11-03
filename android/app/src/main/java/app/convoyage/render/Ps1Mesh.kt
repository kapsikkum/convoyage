package app.convoyage.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One surface's look: a linear tint, a texture id (0 = none, see [Ps1Textures]) and a flag (0 plain, 1 paint: gets the finish, 2 unlit). */
class Surf(val r: Float, val g: Float, val b: Float, val tex: Int = 0, val flag: Int = 0) {
    companion object {
        fun srgb(rgb: Int, tex: Int = 0, flag: Int = 0): Surf {
            val l = Ps1Textures.linear(rgb)
            return Surf(l[0], l[1], l[2], tex, flag)
        }
    }
}

/**
 * A finished model: interleaved vertices (position 3, normal 3, uv 2, tint 3, tex + flag 2) and 16-bit indices.
 * The first [groundIndexCount] indices are the static ground; the rest turn with the vehicle.
 */
class Ps1Mesh(val vertices: FloatArray, val indices: ShortArray, val groundIndexCount: Int) {
    val vertexCount: Int get() = vertices.size / STRIDE

    companion object {
        const val STRIDE = 13
    }
}

/** Collects vertices and triangles. */
class MeshBuilder {
    private var data = FloatArray(Ps1Mesh.STRIDE * 256)
    private var count = 0
    private var idx = ShortArray(768)
    private var idxCount = 0

    val vertexCount: Int get() = count
    val indexCount: Int get() = idxCount

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float, s: Surf): Int {
        if ((count + 1) * Ps1Mesh.STRIDE > data.size) data = data.copyOf(data.size * 2)
        val o = count * Ps1Mesh.STRIDE
        data[o] = x; data[o + 1] = y; data[o + 2] = z
        data[o + 3] = nx; data[o + 4] = ny; data[o + 5] = nz
        data[o + 6] = u; data[o + 7] = v
        data[o + 8] = s.r; data[o + 9] = s.g; data[o + 10] = s.b
        data[o + 11] = s.tex.toFloat(); data[o + 12] = s.flag.toFloat()
        return count++
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (idxCount + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[idxCount++] = a.toShort(); idx[idxCount++] = b.toShort(); idx[idxCount++] = c.toShort()
    }

    /** Rotates (about z, then y) and moves every vertex added since [from], normals too. */
    fun xform(from: Int, rotY: Float = 0f, rotZ: Float = 0f, tx: Float = 0f, ty: Float = 0f, tz: Float = 0f) {
        val cz = cos(rotZ); val sz = sin(rotZ); val cy = cos(rotY); val sy = sin(rotY)
        for (i in from until count) {
            val o = i * Ps1Mesh.STRIDE
            for (k in intArrayOf(0, 3)) {
                val x = data[o + k]; val y = data[o + k + 1]; val z = data[o + k + 2]
                val x1 = x * cz - y * sz; val y1 = x * sz + y * cz
                val x2 = x1 * cy + z * sy; val z2 = -x1 * sy + z * cy
                data[o + k] = x2; data[o + k + 1] = y1; data[o + k + 2] = z2
            }
            data[o] += tx; data[o + 1] += ty; data[o + 2] += tz
        }
    }

    fun build(groundIndexCount: Int): Ps1Mesh = Ps1Mesh(data.copyOf(count * Ps1Mesh.STRIDE), idx.copyOf(idxCount), groundIndexCount)
}

/** Shape helpers. Units are ps1.html's: the car is about 7 long and the wheels sit on y = 0 - radius. */
object Ps1Geo {
    /** The profile pixel space of ps1.html (20 px per unit, x centre 75, y baseline 48) to model units. */
    fun profile(px: List<Pair<Float, Float>>): List<Pair<Float, Float>> = px.map { (x, y) -> Pair((x - 75f) / 20f, (48f - y) / 20f) }

    fun area(p: List<Pair<Float, Float>>): Float {
        var a = 0f
        for (i in p.indices) {
            val (x0, y0) = p[i]; val (x1, y1) = p[(i + 1) % p.size]
            a += x0 * y1 - x1 * y0
        }
        return a / 2f
    }

    /** Ear-clipping triangulation of a simple polygon; returns index triples into [poly] (either winding). */
    fun triangulate(poly: List<Pair<Float, Float>>): List<IntArray> {
        val ccw = area(poly) > 0
        val idx = poly.indices.toMutableList().also { if (!ccw) it.reverse() }
        val out = ArrayList<IntArray>()
        fun cross(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>) =
            (b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)
        fun inside(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>) =
            cross(a, b, p) >= 0 && cross(b, c, p) >= 0 && cross(c, a, p) >= 0
        var guard = 0
        while (idx.size > 3 && guard++ < 10_000) {
            var clipped = false
            for (i in idx.indices) {
                val ia = idx[(i + idx.size - 1) % idx.size]; val ib = idx[i]; val ic = idx[(i + 1) % idx.size]
                val a = poly[ia]; val b = poly[ib]; val c = poly[ic]
                if (cross(a, b, c) <= 1e-6f) continue
                val blocked = idx.any { j -> j != ia && j != ib && j != ic && inside(poly[j], a, b, c) }
                if (blocked) continue
                out.add(intArrayOf(ia, ib, ic))
                idx.removeAt(i)
                clipped = true
                break
            }
            if (!clipped) { // degenerate input: drop the strictest test rather than loop forever
                out.add(intArrayOf(idx[idx.size - 1], idx[0], idx[1]))
                idx.removeAt(0)
            }
        }
        if (idx.size == 3) out.add(intArrayOf(idx[0], idx[1], idx[2]))
        return out
    }

    /** A flat triangle with its normal from the geometry, turned to face ([hx],[hy],[hz]). Degenerate triangles are dropped. */
    fun flatTri(
        mb: MeshBuilder, s: Surf,
        a: FloatArray, b: FloatArray, c: FloatArray,
        uva: FloatArray, uvb: FloatArray, uvc: FloatArray,
        hx: Float, hy: Float, hz: Float,
    ) {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-7f) return
        nx /= len; ny /= len; nz /= len
        val flip = nx * hx + ny * hy + nz * hz < 0
        if (flip) { nx = -nx; ny = -ny; nz = -nz }
        val ia = mb.vertex(a[0], a[1], a[2], nx, ny, nz, uva[0], uva[1], s)
        val ib = mb.vertex(b[0], b[1], b[2], nx, ny, nz, uvb[0], uvb[1], s)
        val ic = mb.vertex(c[0], c[1], c[2], nx, ny, nz, uvc[0], uvc[1], s)
        if (flip) mb.tri(ia, ic, ib) else mb.tri(ia, ib, ic)
    }

    /**
     * The extruded side profile of ps1.html: a polygon (in its pixel space) pushed out along z by [depth], every vertex
     * above [taperY] pulled towards the middle by [taper] so the roof is narrower. UVs project the profile from the
     * side. Normals are flat, taken after the taper.
     */
    fun extrude(mb: MeshBuilder, px: List<Pair<Float, Float>>, depth: Float, taper: Float, s: Surf, taperY: Float = 0.95f, dy: Float = 0f) {
        val p = profile(px).let { if (area(it) < 0) it.reversed() else it }
        val half = depth / 2f
        fun at(i: Int, z: Float): FloatArray {
            val (x, y) = p[i]
            return floatArrayOf(x, y + dy, if (y > taperY) z * taper else z)
        }
        fun uv(i: Int) = floatArrayOf(p[i].first / 7f + 0.5f, p[i].second / 2f)
        for (t in triangulate(p)) {
            val i = t[0]; val j = t[1]; val k = t[2]
            flatTri(mb, s, at(i, half), at(j, half), at(k, half), uv(i), uv(j), uv(k), 0f, 0f, 1f)
            flatTri(mb, s, at(i, -half), at(j, -half), at(k, -half), uv(i), uv(j), uv(k), 0f, 0f, -1f)
        }
        for (i in p.indices) {
            val j = (i + 1) % p.size
            val ex = p[j].first - p[i].first; val ey = p[j].second - p[i].second
            // The outward normal of a counter-clockwise edge is (dy, -dx).
            val hx = ey; val hy = -ex
            val a0 = at(i, half); val b0 = at(j, half); val a1 = at(i, -half); val b1 = at(j, -half)
            flatTri(mb, s, a0, b0, b1, uv(i), uv(j), uv(j), hx, hy, 0f)
            flatTri(mb, s, a0, b1, a1, uv(i), uv(j), uv(i), hx, hy, 0f)
        }
    }

    /** A box centred on ([cx],[cy],[cz]), optionally turned [rotZ] radians about its own centre. Flat faces, UVs 0..1. */
    fun box(mb: MeshBuilder, cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, s: Surf, rotZ: Float = 0f) {
        val from = mb.vertexCount
        val x = sx / 2; val y = sy / 2; val z = sz / 2
        fun face(nx: Float, ny: Float, nz: Float, vararg c: Float) {
            val i = IntArray(4) { k ->
                mb.vertex(c[k * 3], c[k * 3 + 1], c[k * 3 + 2], nx, ny, nz, if (k == 1 || k == 2) 1f else 0f, if (k >= 2) 1f else 0f, s)
            }
            mb.tri(i[0], i[1], i[2]); mb.tri(i[0], i[2], i[3])
        }
        face(0f, 0f, 1f, -x, -y, z, x, -y, z, x, y, z, -x, y, z)
        face(0f, 0f, -1f, x, -y, -z, -x, -y, -z, -x, y, -z, x, y, -z)
        face(1f, 0f, 0f, x, -y, z, x, -y, -z, x, y, -z, x, y, z)
        face(-1f, 0f, 0f, -x, -y, -z, -x, -y, z, -x, y, z, -x, y, -z)
        face(0f, 1f, 0f, -x, y, z, x, y, z, x, y, -z, -x, y, -z)
        face(0f, -1f, 0f, -x, -y, -z, x, -y, -z, x, -y, z, -x, -y, z)
        mb.xform(from, rotZ = rotZ, tx = cx, ty = cy, tz = cz)
    }

    /**
     * A [segments]-sided wheel with its axle along z at ([cx],[cy],[cz]). Smooth normals round the tyre, flat normals and
     * a circular UV map on the two faces. [rotY] turns the axle (for a spare wheel on a tailgate).
     */
    fun wheel(mb: MeshBuilder, cx: Float, cy: Float, cz: Float, radius: Float, width: Float, tyre: Surf, rim: Surf, segments: Int = 8, rotY: Float = 0f) {
        val from = mb.vertexCount
        val hw = width / 2
        for (i in 0 until segments) {
            val a0 = (i * 2 * PI / segments).toFloat(); val a1 = ((i + 1) * 2 * PI / segments).toFloat()
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val v00 = mb.vertex(radius * c0, radius * s0, hw, c0, s0, 0f, i / segments.toFloat(), 1f, tyre)
            val v10 = mb.vertex(radius * c1, radius * s1, hw, c1, s1, 0f, (i + 1) / segments.toFloat(), 1f, tyre)
            val v01 = mb.vertex(radius * c0, radius * s0, -hw, c0, s0, 0f, i / segments.toFloat(), 0f, tyre)
            val v11 = mb.vertex(radius * c1, radius * s1, -hw, c1, s1, 0f, (i + 1) / segments.toFloat(), 0f, tyre)
            mb.tri(v00, v10, v11); mb.tri(v00, v11, v01)
        }
        for (side in intArrayOf(1, -1)) {
            val z = hw * side
            val centre = mb.vertex(0f, 0f, z, 0f, 0f, side.toFloat(), 0.5f, 0.5f, rim)
            val ring = IntArray(segments) { i ->
                val a = (i * 2 * PI / segments).toFloat()
                mb.vertex(radius * cos(a), radius * sin(a), z, 0f, 0f, side.toFloat(), cos(a) * 0.5f + 0.5f, sin(a) * 0.5f + 0.5f, rim)
            }
            for (i in 0 until segments) mb.tri(centre, ring[i], ring[(i + 1) % segments])
        }
        mb.xform(from, rotY = rotY, tx = cx, ty = cy, tz = cz)
    }

    /** A flat fan on the XZ plane at height [y], like THREE.CircleGeometry; UVs are the circle mapped to 0..[uvScale]. */
    fun disc(mb: MeshBuilder, y: Float, rx: Float, rz: Float, segments: Int, s: Surf, uvScale: Float = 1f) {
        val centre = mb.vertex(0f, y, 0f, 0f, 1f, 0f, 0.5f * uvScale, 0.5f * uvScale, s)
        val ring = IntArray(segments) { i ->
            val a = (i * 2 * PI / segments).toFloat()
            mb.vertex(rx * cos(a), y, rz * sin(a), 0f, 1f, 0f, (cos(a) * 0.5f + 0.5f) * uvScale, (sin(a) * 0.5f + 0.5f) * uvScale, s)
        }
        for (i in 0 until segments) mb.tri(centre, ring[(i + 1) % segments], ring[i])
    }
}
