package app.convoyage.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Per-body framing numbers for the camera and the ground. */
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
 * lights at +x), y is up and z is across. A car is about 7 units long and its wheels sit on y = -radius.
 */
object Ps1Models {
    /** Body height change per millimetre of ride height. */
    private const val MM = 0.0016f

    private val TYRE = Surf.srgb(0x16181C)
    private val GLASS = Surf.srgb(0x0E1A28)
    private val DARK = Surf.srgb(0x0A0D12)
    private val ARCH = Surf.srgb(0x050608)
    private val TRIM = Surf.srgb(0x3A414C)
    private val CHROME = Surf.srgb(0xB8BCC2)
    private val PLATE = Surf.srgb(0xE8EAEE)
    private val SEAT = Surf.srgb(0x15171C)
    private val HEAD = Surf.srgb(0xDFF9FF, flag = 2)
    private val TAIL = Surf.srgb(0xFF3D5A, flag = 2)
    private val GROUND = Surf.srgb(0xFFFFFF, tex = Ps1Textures.GROUND)
    private val SHADOW = Surf.srgb(0x05080D)

    fun frame(body: Ps1Body): Ps1Frame {
        val r = spec(body).wheelR
        return when (body.kind) {
            Ps1Kind.Motorcycle -> Ps1Frame(fitRadius = if (body == Ps1Body.Cruiser) 2.9f else 2.7f, groundY = -r, groundRadius = 3.6f, lookY = 0.45f)
            Ps1Kind.Fourwd -> Ps1Frame(fitRadius = 4.0f, groundY = -r, groundRadius = 5.0f, lookY = 0.85f)
            Ps1Kind.Ute -> Ps1Frame(fitRadius = 3.9f, groundY = -r, groundRadius = 5.0f, lookY = 0.45f)
            Ps1Kind.Car -> Ps1Frame(fitRadius = if (body == Ps1Body.Van || body == Ps1Body.Suv) 3.9f else 3.6f, groundY = -r, groundRadius = 4.6f, lookY = if (body == Ps1Body.Van) 0.8f else 0.4f)
        }
    }

    /** Wheels and size of a body: where the axles are, how big the wheels, how wide the track. */
    private class Spec(val wheelR: Float, val axles: FloatArray, val track: Float, val wheelW: Float)

    private fun spec(body: Ps1Body): Spec = when (body) {
        Ps1Body.Sedan -> Spec(0.52f, floatArrayOf(-2.05f, 2.1f), 1.45f, 0.42f)
        Ps1Body.Hatch -> Spec(0.5f, floatArrayOf(-1.75f, 1.85f), 1.42f, 0.4f)
        Ps1Body.HotHatch -> Spec(0.54f, floatArrayOf(-1.75f, 1.85f), 1.5f, 0.46f)
        Ps1Body.Wagon -> Spec(0.52f, floatArrayOf(-2.1f, 2.1f), 1.45f, 0.42f)
        Ps1Body.Coupe -> Spec(0.52f, floatArrayOf(-2.0f, 2.15f), 1.48f, 0.44f)
        Ps1Body.Roadster -> Spec(0.5f, floatArrayOf(-1.9f, 1.95f), 1.42f, 0.42f)
        Ps1Body.Muscle -> Spec(0.56f, floatArrayOf(-2.1f, 2.2f), 1.52f, 0.48f)
        Ps1Body.Suv -> Spec(0.62f, floatArrayOf(-2.15f, 2.15f), 1.55f, 0.48f)
        Ps1Body.Van -> Spec(0.56f, floatArrayOf(-2.4f, 2.3f), 1.55f, 0.44f)
        Ps1Body.SingleCab, Ps1Body.DualCab -> Spec(0.58f, floatArrayOf(-2.3f, 2.35f), 1.5f, 0.46f)
        Ps1Body.FourwdWagon, Ps1Body.FourwdUte -> Spec(0.68f, floatArrayOf(-2.25f, 2.3f), 1.62f, 0.56f)
        Ps1Body.SportBike, Ps1Body.Naked -> Spec(0.62f, floatArrayOf(-1.5f, 1.55f), 0f, 0.24f)
        Ps1Body.Cruiser -> Spec(0.6f, floatArrayOf(-1.75f, 1.9f), 0f, 0.3f)
        Ps1Body.Adventure -> Spec(0.7f, floatArrayOf(-1.55f, 1.65f), 0f, 0.22f)
    }

    fun build(spec: Ps1Spec): Ps1Mesh {
        val mb = MeshBuilder()
        val body = spec.body
        val f = frame(body)
        Ps1Geo.disc(mb, f.groundY, f.groundRadius, f.groundRadius, 12, GROUND, uvScale = 6f)
        val groundIndices = mb.indexCount

        val paint = Surf.srgb(spec.paint, Ps1Textures.BODY, 1)
        val rim = Surf.srgb(spec.wheel.rgb, Ps1Textures.RIM)
        val dy = spec.rideHeightMm * MM
        val s = spec(body)
        if (body.kind == Ps1Kind.Motorcycle) bike(mb, body, s, paint, rim, dy) else car(mb, body, s, paint, rim, dy)

        // The shadow blob under the vehicle, a hair above the ground.
        val bike = body.kind == Ps1Kind.Motorcycle
        Ps1Geo.disc(mb, f.groundY + 0.012f, if (bike) 2.5f else 3.5f, if (bike) 0.75f else 1.6f, 10, SHADOW)
        return mb.build(groundIndices)
    }

    // ---- four wheels ------------------------------------------------------------------------------------------

    private fun car(mb: MeshBuilder, body: Ps1Body, s: Spec, paint: Surf, rim: Surf, dy: Float) {
        val w = s.track
        val st = stations(body, w).map { St(it.x, it.sill + dy, it.belt + dy, it.roof + dy, it.w, it.pillar) }
        loft(mb, st, paint)
        val rear = st.first()
        val front = st.last()
        // Arches behind each wheel, then the wheels flush with the body side.
        val wz = w - s.wheelW / 2f + 0.03f
        for (x in s.axles) for (side in intArrayOf(1, -1)) {
            arch(mb, x, 0f, side * (w + 0.006f), s.wheelR * 1.2f, side)
            Ps1Geo.wheel(mb, x, 0f, side * wz, s.wheelR, s.wheelW, TYRE, rim)
        }
        // Lights, grille and plates on the ends.
        val fx = front.x
        val fy = (front.sill + front.belt) / 2f
        for (z in floatArrayOf(w * 0.68f, -w * 0.68f)) Ps1Geo.box(mb, fx + 0.02f, front.belt - 0.12f, z, 0.08f, 0.14f, w * 0.42f, HEAD)
        Ps1Geo.box(mb, fx + 0.03f, fy - 0.02f, 0f, 0.06f, 0.24f, w * 0.85f, DARK)
        Ps1Geo.box(mb, fx + 0.05f, front.sill + 0.12f, 0f, 0.04f, 0.14f, 0.55f, PLATE)
        val rx = rear.x
        for (z in floatArrayOf(w * 0.7f, -w * 0.7f)) Ps1Geo.box(mb, rx - 0.02f, rear.belt - 0.1f, z, 0.08f, 0.14f, w * 0.4f, TAIL)
        Ps1Geo.box(mb, rx - 0.05f, rear.sill + 0.2f, 0f, 0.04f, 0.14f, 0.55f, PLATE)
        // Mirrors at the foot of the A pillar.
        st.lastOrNull { it.cabin }?.let { a ->
            val m = st.firstOrNull { it.x > a.x } ?: a
            val mx = (a.x + m.x) / 2f
            for (side in intArrayOf(1, -1)) Ps1Geo.box(mb, mx, a.belt + 0.1f, side * (w + 0.12f), 0.16f, 0.12f, 0.2f, paint)
        }
        extras(mb, body, st, s, paint, rim, dy)
    }

    /** The cross-sections of each four-wheeled body, rear to front. */
    private fun stations(body: Ps1Body, w: Float): List<St> = when (body) {
        Ps1Body.Sedan -> listOf(
            St(-3.45f, 0.05f, 0.62f, 0.62f, w * 0.96f), St(-3.35f, -0.12f, 0.8f, 0.8f, w), St(-2.4f, -0.12f, 0.86f, 0.86f, w),
            St(-1.45f, -0.12f, 0.88f, 0.88f, w), St(-0.65f, -0.12f, 0.88f, 1.6f, w), St(0f, -0.12f, 0.88f, 1.62f, w, pillar = true),
            St(0.12f, -0.12f, 0.88f, 1.62f, w), St(0.85f, -0.12f, 0.88f, 1.6f, w), St(1.65f, -0.12f, 0.85f, 0.85f, w),
            St(3.15f, -0.12f, 0.7f, 0.7f, w), St(3.45f, 0f, 0.55f, 0.55f, w * 0.95f),
        )
        Ps1Body.Hatch, Ps1Body.HotHatch -> {
            val low = if (body == Ps1Body.HotHatch) -0.06f else 0f
            listOf(
                St(-3.0f, -0.05f + low, 0.62f + low, 0.62f + low, w * 0.95f), St(-2.95f, -0.12f + low, 0.86f + low, 0.86f + low, w),
                St(-2.82f, -0.12f + low, 0.9f + low, 1.5f + low, w), St(-2.55f, -0.12f + low, 0.9f + low, 1.62f + low, w),
                St(-0.3f, -0.12f + low, 0.9f + low, 1.65f + low, w, pillar = true), St(-0.18f, -0.12f + low, 0.9f + low, 1.65f + low, w),
                St(0.7f, -0.12f + low, 0.9f + low, 1.6f + low, w), St(1.6f, -0.12f + low, 0.86f + low, 0.86f + low, w),
                St(2.75f, -0.12f + low, 0.72f + low, 0.72f + low, w), St(3.0f, 0f + low, 0.55f + low, 0.55f + low, w * 0.95f),
            )
        }
        Ps1Body.Wagon -> listOf(
            St(-3.55f, 0.02f, 0.62f, 0.62f, w * 0.96f), St(-3.48f, -0.12f, 0.86f, 0.86f, w), St(-3.36f, -0.12f, 0.88f, 1.55f, w),
            St(-2.3f, -0.12f, 0.88f, 1.62f, w), St(-1.05f, -0.12f, 0.88f, 1.63f, w, pillar = true), St(-0.93f, -0.12f, 0.88f, 1.63f, w),
            St(0f, -0.12f, 0.88f, 1.63f, w, pillar = true), St(0.12f, -0.12f, 0.88f, 1.63f, w), St(0.85f, -0.12f, 0.88f, 1.6f, w),
            St(1.7f, -0.12f, 0.85f, 0.85f, w), St(3.2f, -0.12f, 0.7f, 0.7f, w), St(3.5f, 0f, 0.55f, 0.55f, w * 0.95f),
        )
        Ps1Body.Coupe -> listOf(
            St(-3.35f, 0f, 0.6f, 0.6f, w * 0.95f), St(-3.25f, -0.15f, 0.8f, 0.8f, w), St(-2.6f, -0.15f, 0.84f, 0.84f, w),
            St(-0.5f, -0.15f, 0.86f, 1.42f, w), St(0.6f, -0.15f, 0.86f, 1.42f, w), St(1.45f, -0.15f, 0.82f, 0.82f, w),
            St(3.1f, -0.15f, 0.62f, 0.62f, w), St(3.4f, -0.05f, 0.48f, 0.48f, w * 0.94f),
        )
        Ps1Body.Roadster -> listOf(
            St(-3.2f, 0f, 0.6f, 0.6f, w * 0.95f), St(-3.1f, -0.15f, 0.78f, 0.78f, w), St(-1.4f, -0.15f, 0.82f, 0.82f, w),
            St(0.8f, -0.15f, 0.8f, 0.8f, w), St(3.0f, -0.15f, 0.62f, 0.62f, w), St(3.3f, -0.05f, 0.48f, 0.48f, w * 0.94f),
        )
        Ps1Body.Muscle -> listOf(
            St(-3.5f, 0.02f, 0.66f, 0.66f, w * 0.97f), St(-3.42f, -0.1f, 0.88f, 0.88f, w), St(-2.5f, -0.1f, 0.92f, 0.92f, w),
            St(-1.6f, -0.1f, 0.92f, 0.92f, w), St(-0.6f, -0.1f, 0.92f, 1.5f, w), St(0.6f, -0.1f, 0.92f, 1.5f, w),
            St(1.4f, -0.1f, 0.9f, 0.9f, w), St(3.3f, -0.1f, 0.84f, 0.84f, w), St(3.55f, 0.04f, 0.64f, 0.64f, w * 0.97f),
        )
        Ps1Body.Suv -> listOf(
            St(-3.45f, 0.18f, 0.98f, 0.98f, w * 0.96f), St(-3.4f, 0.1f, 1.15f, 1.15f, w), St(-3.28f, 0.1f, 1.17f, 1.9f, w),
            St(-1.1f, 0.1f, 1.17f, 2.02f, w, pillar = true), St(-0.98f, 0.1f, 1.17f, 2.02f, w), St(0.25f, 0.1f, 1.17f, 2.02f, w, pillar = true),
            St(0.37f, 0.1f, 1.17f, 2.02f, w), St(1.05f, 0.1f, 1.17f, 1.98f, w), St(1.95f, 0.1f, 1.12f, 1.12f, w),
            St(3.2f, 0.1f, 1.0f, 1.0f, w), St(3.45f, 0.2f, 0.85f, 0.85f, w * 0.96f),
        )
        Ps1Body.Van -> listOf(
            St(-3.7f, 0.05f, 1.05f, 2.45f, w * 0.98f), St(-3.6f, 0.05f, 1.1f, 2.55f, w, pillar = true), St(0.9f, 0.05f, 1.1f, 2.55f, w),
            St(1.3f, 0.05f, 1.1f, 2.52f, w), St(2.5f, 0.05f, 1.05f, 1.05f, w), St(3.45f, 0.05f, 0.85f, 0.85f, w),
            St(3.7f, 0.2f, 0.72f, 0.72f, w * 0.96f),
        )
        // Utes and 4WD utes: the cab only; the tray is added in extras.
        Ps1Body.SingleCab -> listOf(
            St(-0.6f, 0.02f, 1.0f, 1.0f, w), St(-0.5f, 0.02f, 1.0f, 1.75f, w), St(0.6f, 0.02f, 1.0f, 1.78f, w),
            St(1.5f, 0.02f, 0.98f, 0.98f, w), St(3.3f, 0.02f, 0.86f, 0.86f, w), St(3.6f, 0.12f, 0.7f, 0.7f, w * 0.96f),
        )
        Ps1Body.DualCab -> listOf(
            St(-1.75f, 0.02f, 1.0f, 1.0f, w), St(-1.65f, 0.02f, 1.0f, 1.75f, w), St(-0.4f, 0.02f, 1.0f, 1.78f, w, pillar = true),
            St(-0.28f, 0.02f, 1.0f, 1.78f, w), St(0.6f, 0.02f, 1.0f, 1.78f, w), St(1.5f, 0.02f, 0.98f, 0.98f, w),
            St(3.3f, 0.02f, 0.86f, 0.86f, w), St(3.6f, 0.12f, 0.7f, 0.7f, w * 0.96f),
        )
        Ps1Body.FourwdWagon -> listOf(
            St(-3.4f, 0.35f, 1.3f, 1.3f, w), St(-3.35f, 0.35f, 1.42f, 2.28f, w), St(-1.0f, 0.35f, 1.42f, 2.32f, w, pillar = true),
            St(-0.86f, 0.35f, 1.42f, 2.32f, w), St(0.3f, 0.35f, 1.42f, 2.32f, w, pillar = true), St(0.44f, 0.35f, 1.42f, 2.32f, w),
            St(1.0f, 0.35f, 1.42f, 2.3f, w), St(1.75f, 0.35f, 1.38f, 1.38f, w), St(3.3f, 0.35f, 1.26f, 1.26f, w),
            St(3.5f, 0.45f, 1.1f, 1.1f, w * 0.97f),
        )
        Ps1Body.FourwdUte -> listOf(
            St(-1.75f, 0.35f, 1.4f, 1.4f, w), St(-1.65f, 0.35f, 1.4f, 2.22f, w), St(-0.4f, 0.35f, 1.4f, 2.25f, w, pillar = true),
            St(-0.26f, 0.35f, 1.4f, 2.25f, w), St(0.7f, 0.35f, 1.4f, 2.24f, w), St(1.55f, 0.35f, 1.36f, 1.36f, w),
            St(3.3f, 0.35f, 1.24f, 1.24f, w), St(3.5f, 0.45f, 1.08f, 1.08f, w * 0.97f),
        )
        else -> emptyList()
    }

    /** What makes each body itself beyond its shell. */
    private fun extras(mb: MeshBuilder, body: Ps1Body, st: List<St>, s: Spec, paint: Surf, rim: Surf, dy: Float) {
        val w = s.track
        val roofTop = st.maxOf { it.roof }
        when (body) {
            Ps1Body.Wagon -> for (z in floatArrayOf(w * 0.62f, -w * 0.62f)) Ps1Geo.box(mb, -1.0f, roofTop + 0.05f, z, 3.6f, 0.06f, 0.08f, DARK)
            Ps1Body.Suv -> {
                for (z in floatArrayOf(w * 0.62f, -w * 0.62f)) Ps1Geo.box(mb, -0.9f, roofTop + 0.06f, z, 3.4f, 0.07f, 0.09f, DARK)
                bumpers(mb, st, w, 0.26f)
            }
            Ps1Body.HotHatch -> {
                Ps1Geo.box(mb, st[2].x - 0.05f, roofTop - 0.02f, 0f, 0.4f, 0.06f, w * 1.6f, paint)
                Ps1Geo.box(mb, st.last().x - 0.1f, st.last().sill + 0.12f, 0f, 0.2f, 0.16f, w * 1.7f, DARK)
            }
            Ps1Body.Muscle -> {
                Ps1Geo.box(mb, 2.35f, 0.95f + dy, 0f, 0.9f, 0.14f, 0.7f, paint)
                Ps1Geo.box(mb, 2.82f, 0.95f + dy, 0f, 0.04f, 0.1f, 0.55f, DARK)
                Ps1Geo.box(mb, -3.25f, 1.08f + dy, 0f, 0.3f, 0.05f, w * 1.8f, paint)
                for (z in floatArrayOf(0.9f, -0.9f)) Ps1Geo.box(mb, -3.2f, 0.98f + dy, z, 0.08f, 0.16f, 0.08f, DARK)
                // Twin stripes.
                for (z in floatArrayOf(0.22f, -0.22f)) Ps1Geo.box(mb, 1.3f, 0.93f + dy, z, 4.0f, 0.01f, 0.18f, PLATE)
            }
            Ps1Body.Coupe -> Ps1Geo.box(mb, -3.15f, 0.86f + dy, 0f, 0.25f, 0.05f, w * 1.6f, paint)
            Ps1Body.Roadster -> {
                Ps1Geo.box(mb, -0.4f, 0.83f + dy, 0f, 1.9f, 0.04f, w * 1.55f, DARK)
                for (z in floatArrayOf(0.5f, -0.5f)) {
                    Ps1Geo.box(mb, -0.75f, 1.05f + dy, z, 0.25f, 0.5f, 0.55f, SEAT)
                    Ps1Geo.box(mb, -1.05f, 1.2f + dy, z, 0.08f, 0.6f, 0.08f, CHROME)
                }
                Ps1Geo.box(mb, 0.75f, 1.08f + dy, 0f, 0.05f, 0.5f, w * 1.7f, GLASS, rotZ = 0.55f)
            }
            Ps1Body.Van -> {
                for (z in floatArrayOf(0.55f, -0.55f)) Ps1Geo.box(mb, -3.72f, 1.85f + dy, z, 0.02f, 0.6f, 0.75f, GLASS)
                bumpers(mb, st, w, 0.22f)
            }
            Ps1Body.SingleCab, Ps1Body.DualCab -> tray(mb, st.first().x - 0.08f, -3.5f, w, 0.25f + dy, 0.95f + dy, paint)
            Ps1Body.FourwdUte -> {
                tray(mb, st.first().x - 0.08f, -3.45f, w, 0.55f + dy, 1.3f + dy, paint)
                // Sports bar over the tray, then the 4WD kit.
                for (z in floatArrayOf(w * 0.85f, -w * 0.85f)) Ps1Geo.box(mb, st.first().x - 0.35f, 1.6f + dy, z, 0.1f, 0.6f, 0.1f, DARK)
                Ps1Geo.box(mb, st.first().x - 0.35f, 1.9f + dy, 0f, 0.1f, 0.1f, w * 1.8f, DARK)
                fourwdKit(mb, st, w, dy, spare = false, rim = rim)
            }
            Ps1Body.FourwdWagon -> {
                Ps1Geo.box(mb, -0.6f, roofTop + 0.08f + 0f, 0f, 3.4f, 0.08f, w * 1.5f, DARK)
                for (z in floatArrayOf(w * 0.72f, -w * 0.72f)) Ps1Geo.box(mb, -0.6f, roofTop + 0.16f, z, 3.4f, 0.08f, 0.06f, DARK)
                fourwdKit(mb, st, w, dy, spare = true, rim = rim)
            }
            else -> Unit
        }
    }

    /** Roo bar, wide plastic arches, a snorkel up the right A pillar, and a spare on the back. */
    private fun fourwdKit(mb: MeshBuilder, st: List<St>, w: Float, dy: Float, spare: Boolean, rim: Surf) {
        val f = st.last()
        Ps1Geo.box(mb, f.x + 0.14f, f.sill + 0.3f, 0f, 0.14f, 0.55f, w * 1.85f, DARK)
        for (z in floatArrayOf(0.45f, -0.45f)) Ps1Geo.box(mb, f.x + 0.16f, f.belt - 0.05f, z, 0.08f, 0.5f, 0.08f, DARK)
        val a = st.lastOrNull { it.cabin } ?: f
        Ps1Geo.box(mb, a.x + 0.25f, (a.belt + a.roof) / 2f, -(w + 0.08f), 0.14f, a.roof - a.belt + 0.6f, 0.14f, DARK)
        Ps1Geo.box(mb, a.x + 0.25f, a.roof + 0.05f, -(w + 0.08f), 0.3f, 0.14f, 0.16f, DARK)
        bumpers(mb, st, w, 0.3f)
        if (spare) Ps1Geo.wheel(mb, st.first().x - 0.18f, 1.25f + dy, 0f, 0.62f, 0.3f, TYRE, rim, rotY = (PI / 2).toFloat())
    }

    private fun bumpers(mb: MeshBuilder, st: List<St>, w: Float, h: Float) {
        val f = st.last(); val r = st.first()
        Ps1Geo.box(mb, f.x + 0.05f, f.sill + h / 2f, 0f, 0.14f, h, w * 1.95f, TRIM)
        Ps1Geo.box(mb, r.x - 0.05f, r.sill + h / 2f, 0f, 0.14f, h, w * 1.95f, TRIM)
    }

    /** A ute tray from [x0] back to [x1]: floor, liner, two sides and a tailgate. */
    private fun tray(mb: MeshBuilder, x0: Float, x1: Float, w: Float, floorY: Float, topY: Float, paint: Surf) {
        val len = x0 - x1
        val cx = (x0 + x1) / 2f
        Ps1Geo.box(mb, cx, floorY - 0.18f, 0f, len, 0.36f, w * 1.95f, TRIM)
        Ps1Geo.box(mb, cx, floorY + 0.02f, 0f, len, 0.06f, w * 1.9f, DARK)
        val wallH = topY - floorY
        for (z in floatArrayOf(w * 0.97f, -w * 0.97f)) Ps1Geo.box(mb, cx, floorY + wallH / 2f, z, len, wallH, 0.12f, paint)
        Ps1Geo.box(mb, x1 + 0.06f, floorY + wallH / 2f, 0f, 0.12f, wallH, w * 1.95f, paint)
        for (z in floatArrayOf(w * 0.7f, -w * 0.7f)) Ps1Geo.box(mb, x1 - 0.01f, topY - 0.15f, z, 0.06f, 0.2f, 0.3f, TAIL)
        Ps1Geo.box(mb, x1 - 0.02f, floorY + 0.1f, 0f, 0.04f, 0.14f, 0.55f, PLATE)
    }

    // ---- loft --------------------------------------------------------------------------------------------------

    /** The ring of points of one station: (y, z) from the bottom on the +z side round the top to the -z side. */
    private fun ring(s: St): List<Pair<Float, Float>> {
        val c0 = Pair(s.sill, s.w * 0.9f)
        val c1 = Pair(s.sill + 0.22f * (s.belt - s.sill), s.w)
        val c2 = Pair(s.belt, s.w * 0.96f)
        val c3 = if (s.cabin) Pair(s.roof, s.w * 0.76f) else Pair(s.belt + 0.03f, s.w * 0.88f)
        return listOf(c0, c1, c2, c3, Pair(c3.first, -c3.second), Pair(c2.first, -c2.second), Pair(c1.first, -c1.second), Pair(c0.first, -c0.second))
    }

    /**
     * Skins consecutive stations: paint below the window line; glass on the side between pillars where both ends
     * have a cabin; glass on top where the roof slopes down to the window line (windscreen, rear window); a dark floor.
     */
    private fun loft(mb: MeshBuilder, st: List<St>, paint: Surf) {
        fun uv(x: Float, y: Float) = floatArrayOf(x / 7f + 0.5f, y / 2f)
        for (i in 0 until st.size - 1) {
            val a = st[i]; val b = st[i + 1]
            val ra = ring(a); val rb = ring(b)
            val yc = (a.sill + maxOf(a.roof, b.roof)) / 2f
            for (k in ra.indices) {
                val k2 = (k + 1) % ra.size
                val surf = when (k) {
                    2, 4 -> if (a.cabin && b.cabin && !a.pillar) GLASS else paint
                    3 -> if (a.cabin != b.cabin) GLASS else paint
                    7 -> DARK
                    else -> paint
                }
                val p0 = floatArrayOf(a.x, ra[k].first, ra[k].second)
                val p1 = floatArrayOf(a.x, ra[k2].first, ra[k2].second)
                val q0 = floatArrayOf(b.x, rb[k].first, rb[k].second)
                val q1 = floatArrayOf(b.x, rb[k2].first, rb[k2].second)
                val hy = (ra[k].first + ra[k2].first) / 2f - yc
                val hz = (ra[k].second + ra[k2].second) / 2f
                Ps1Geo.flatTri(mb, surf, p0, q0, q1, uv(p0[0], p0[1]), uv(q0[0], q0[1]), uv(q1[0], q1[1]), 0f, hy, hz)
                Ps1Geo.flatTri(mb, surf, p0, q1, p1, uv(p0[0], p0[1]), uv(q1[0], q1[1]), uv(p1[0], p1[1]), 0f, hy, hz)
            }
        }
        // Ends: a fan from the middle of each end ring.
        for ((s, dir) in listOf(st.first() to -1f, st.last() to 1f)) {
            val r = ring(s)
            val cy = r.map { it.first }.average().toFloat()
            val c = floatArrayOf(s.x, cy, 0f)
            for (k in r.indices) {
                val k2 = (k + 1) % r.size
                val p = floatArrayOf(s.x, r[k].first, r[k].second)
                val q = floatArrayOf(s.x, r[k2].first, r[k2].second)
                val glassEnd = (k == 3) && s.cabin
                Ps1Geo.flatTri(mb, if (glassEnd) GLASS else paint, c, p, q, uv(c[0], c[1]), uv(p[0], p[1]), uv(q[0], q[1]), dir, 0f, 0f)
            }
        }
    }

    /** The dark wheel-arch opening on a body side: a fan over the top of the wheel, facing out along [side] z. */
    private fun arch(mb: MeshBuilder, cx: Float, cy: Float, z: Float, r: Float, side: Int) {
        val n = 8
        val nz = side.toFloat()
        val centre = mb.vertex(cx, cy - r * 0.15f, z, 0f, 0f, nz, 0.5f, 0.5f, ARCH)
        val ring = IntArray(n + 1) { i ->
            val a = (PI * (-0.08 + 1.16 * i / n)).toFloat()
            mb.vertex(cx + r * cos(a), cy + r * sin(a), z, 0f, 0f, nz, 0f, 0f, ARCH)
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
            else -> Unit
        }
        // Engine, swing arm and exhaust, forks, tail light, then the wheels.
        Ps1Geo.box(mb, 0.1f, 0.5f + dy, 0f, 1.2f, 0.7f, 0.56f, TRIM)
        for (z in floatArrayOf(0.25f, -0.25f)) Ps1Geo.box(mb, (rearX + 0.1f) / 2f, 0.3f + dy * 0.5f, z, -rearX + 0.2f, 0.1f, 0.08f, TRIM, rotZ = 0.12f)
        if (body != Ps1Body.Cruiser) Ps1Geo.box(mb, -0.9f, 0.3f + dy, 0.34f, 1.7f, 0.15f, 0.14f, CHROME, rotZ = if (body == Ps1Body.SportBike) 0.35f else 0.08f)
        val rake = if (body == Ps1Body.Cruiser) 0.62f else 0.4f
        val forkLen = if (body == Ps1Body.Adventure) 1.5f else 1.3f
        for (z in floatArrayOf(0.2f, -0.2f)) Ps1Geo.box(mb, frontX - 0.25f, 0.6f + dy * 0.5f, z, 0.1f, forkLen, 0.09f, CHROME, rotZ = rake)
        Ps1Geo.box(mb, rearX - 0.55f, 1.0f + dy, 0f, 0.1f, 0.14f, 0.3f, TAIL)
        Ps1Geo.wheel(mb, rearX, 0f, 0f, s.wheelR, s.wheelW * (if (body == Ps1Body.Cruiser) 1.3f else 1f), TYRE, rim)
        Ps1Geo.wheel(mb, frontX, 0f, 0f, s.wheelR, s.wheelW, TYRE, rim)
    }
}
