package app.convoyage.render

/** Per-kind framing numbers for the camera and the ground. */
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
 * The four vehicles, built in code the way ps1.html builds its car: extruded side profiles, boxes, and 8-sided wheels.
 * x is forward (the lights at +x), y is up and z is across. Everything is in model units, about 20 px each in the profiles.
 */
object Ps1Models {
    private fun pts(vararg v: Float): List<Pair<Float, Float>> = List(v.size / 2) { Pair(v[it * 2], v[it * 2 + 1]) }

    // Profiles in ps1.html's pixel space.
    private val CAR_BODY = pts(8f, 48f, 9f, 38f, 12f, 34f, 29f, 30f, 47f, 18.5f, 60f, 14f, 72f, 13.5f, 96f, 13.5f, 110f, 16.5f, 116f, 21.5f, 129f, 30f, 139f, 33f, 142f, 40.5f, 142f, 48f)
    private val CAR_GLASS = pts(42f, 29.6f, 51f, 19.6f, 72f, 15.6f, 95f, 15.6f, 106f, 19.5f, 113f, 29.2f)

    private val UTE_CAB = pts(62f, 48f, 62f, 20f, 66f, 14.5f, 74f, 13f, 96f, 13f, 106f, 17f, 116f, 28f, 138f, 31f, 147f, 35f, 150f, 41f, 150f, 48f)
    private val UTE_GLASS = pts(66f, 22f, 69f, 16.5f, 75f, 15f, 96f, 15f, 104f, 18.5f, 111f, 26f)

    private val FWD_BODY = pts(10f, 48f, 10f, 14f, 14f, 9f, 24f, 7f, 104f, 7f, 112f, 12f, 122f, 24f, 145f, 27f, 152f, 31f, 154f, 40f, 154f, 48f)
    private val FWD_PANE_A = pts(15f, 14f, 18f, 10.5f, 54f, 10.5f, 54f, 22f, 15f, 22f)
    private val FWD_PANE_B = pts(60f, 10.5f, 101f, 10.5f, 110f, 16f, 116f, 22f, 60f, 22f)

    // Motorcycle shapes, directly in model units (px coordinates 75 + 20x, 48 - 20y).
    private fun unitsToPx(vararg v: Float): List<Pair<Float, Float>> = List(v.size / 2) { Pair(75f + v[it * 2] * 20f, 48f - v[it * 2 + 1] * 20f) }
    private val BIKE_TANK = unitsToPx(-0.5f, 0.78f, -0.4f, 1.1f, 0.2f, 1.26f, 0.85f, 1.2f, 1.0f, 1.0f, 0.85f, 0.82f)
    private val BIKE_TAIL = unitsToPx(-2.05f, 0.86f, -1.95f, 1.12f, -1.2f, 1.2f, -0.5f, 1.1f, -0.5f, 0.84f)
    private val BIKE_SEAT = unitsToPx(-1.5f, 1.2f, -0.5f, 1.12f, -0.45f, 0.98f, -1.5f, 1.0f)

    fun frame(kind: Ps1Kind): Ps1Frame = when (kind) {
        Ps1Kind.Car -> Ps1Frame(fitRadius = 3.6f, groundY = -0.53f, groundRadius = 4.6f, lookY = 0.35f)
        Ps1Kind.Ute -> Ps1Frame(fitRadius = 3.9f, groundY = -0.58f, groundRadius = 5.0f, lookY = 0.4f)
        Ps1Kind.Fourwd -> Ps1Frame(fitRadius = 3.9f, groundY = -0.68f, groundRadius = 5.0f, lookY = 0.75f)
        Ps1Kind.Motorcycle -> Ps1Frame(fitRadius = 2.7f, groundY = -0.62f, groundRadius = 3.6f, lookY = 0.35f)
    }

    /** Body height change per millimetre of ride height. */
    private const val MM = 0.0016f

    private val TYRE = Surf.srgb(0x16181C)
    private val GLASS = Surf.srgb(0x0B1622)
    private val DARK = Surf.srgb(0x0A0D12)
    private val TRIM = Surf.srgb(0x4A515C)
    private val HEAD = Surf.srgb(0xDFF9FF, flag = 2)
    private val TAIL = Surf.srgb(0xFF3D5A, flag = 2)
    private val GROUND = Surf.srgb(0xFFFFFF, tex = Ps1Textures.GROUND)
    private val SHADOW = Surf.srgb(0x05080D)

    fun build(spec: Ps1Spec): Ps1Mesh {
        val mb = MeshBuilder()
        val f = frame(spec.kind)
        Ps1Geo.disc(mb, f.groundY, f.groundRadius, f.groundRadius, 12, GROUND, uvScale = 6f)
        val groundIndices = mb.indexCount

        val paint = Surf.srgb(spec.paint, Ps1Textures.BODY, 1)
        val rim = Surf.srgb(spec.wheel.rgb, Ps1Textures.RIM)
        val dy = spec.rideHeightMm * MM
        when (spec.kind) {
            Ps1Kind.Car -> car(mb, paint, rim, dy)
            Ps1Kind.Ute -> ute(mb, paint, rim, dy)
            Ps1Kind.Fourwd -> fourwd(mb, paint, rim, dy)
            Ps1Kind.Motorcycle -> bike(mb, paint, rim, dy)
        }
        // The shadow blob under the vehicle, a hair above the ground.
        val sx = if (spec.kind == Ps1Kind.Motorcycle) 2.5f else 3.4f
        val sz = if (spec.kind == Ps1Kind.Motorcycle) 0.75f else 1.45f
        Ps1Geo.disc(mb, f.groundY + 0.012f, sx, sz, 10, SHADOW)
        return mb.build(groundIndices)
    }

    private fun car(mb: MeshBuilder, paint: Surf, rim: Surf, dy: Float) {
        Ps1Geo.extrude(mb, CAR_BODY, 3.1f, 0.78f, paint, dy = dy)
        Ps1Geo.extrude(mb, CAR_GLASS, 3.18f, 0.8f, GLASS, dy = dy + 0.01f)
        for (z in floatArrayOf(0.95f, -0.95f)) Ps1Geo.box(mb, 3.32f, 0.62f + dy, z, 0.12f, 0.16f, 0.7f, HEAD)
        for (z in floatArrayOf(1.0f, -1.0f)) Ps1Geo.box(mb, -3.36f, 0.62f + dy, z, 0.1f, 0.14f, 0.6f, TAIL)
        Ps1Geo.box(mb, 3.37f, 0.32f + dy, 0f, 0.06f, 0.28f, 1.5f, DARK)
        for (x in floatArrayOf(-1.9f, 2.05f)) for (z in floatArrayOf(1.38f, -1.38f)) Ps1Geo.wheel(mb, x, 0f, z, 0.53f, 0.42f, TYRE, rim)
    }

    private fun ute(mb: MeshBuilder, paint: Surf, rim: Surf, dy: Float) {
        Ps1Geo.extrude(mb, UTE_CAB, 3.1f, 0.8f, paint, dy = dy)
        Ps1Geo.extrude(mb, UTE_GLASS, 3.18f, 0.82f, GLASS, dy = dy + 0.01f)
        // The tray: chassis rail, dark liner, two side walls and a tailgate.
        Ps1Geo.box(mb, -1.7f, 0.25f + dy, 0f, 3.4f, 0.5f, 3.0f, TRIM)
        Ps1Geo.box(mb, -1.7f, 0.56f + dy, 0f, 3.4f, 0.12f, 2.9f, DARK)
        for (z in floatArrayOf(1.46f, -1.46f)) Ps1Geo.box(mb, -1.7f, 0.9f + dy, z, 3.4f, 0.58f, 0.14f, paint)
        Ps1Geo.box(mb, -3.34f, 0.9f + dy, 0f, 0.14f, 0.58f, 3.0f, paint)
        for (z in floatArrayOf(1.0f, -1.0f)) Ps1Geo.box(mb, 3.78f, 0.62f + dy, z, 0.12f, 0.16f, 0.6f, HEAD)
        for (z in floatArrayOf(1.3f, -1.3f)) Ps1Geo.box(mb, -3.42f, 0.9f + dy, z, 0.1f, 0.16f, 0.35f, TAIL)
        Ps1Geo.box(mb, 3.78f, 0.32f + dy, 0f, 0.06f, 0.28f, 1.4f, DARK)
        for (x in floatArrayOf(-2.25f, 2.4f)) for (z in floatArrayOf(1.4f, -1.4f)) Ps1Geo.wheel(mb, x, 0f, z, 0.58f, 0.46f, TYRE, rim)
    }

    private fun fourwd(mb: MeshBuilder, paint: Surf, rim: Surf, dy: Float) {
        val lift = 0.3f + dy
        Ps1Geo.extrude(mb, FWD_BODY, 3.4f, 0.88f, paint, dy = lift)
        Ps1Geo.extrude(mb, FWD_PANE_A, 3.48f, 0.9f, GLASS, dy = lift + 0.01f)
        Ps1Geo.extrude(mb, FWD_PANE_B, 3.48f, 0.9f, GLASS, dy = lift + 0.01f)
        // Underbody between the lifted body and the axles.
        Ps1Geo.box(mb, 0.1f, 0.16f + dy, 0f, 6.9f, 0.42f, 2.9f, TRIM)
        // Roof rack, bull bar and a spare on the tailgate.
        Ps1Geo.box(mb, -0.2f, 2.5f + dy, 0f, 3.8f, 0.09f, 2.3f, DARK)
        Ps1Geo.box(mb, 4.0f, 0.62f + lift, 0f, 0.16f, 0.5f, 2.6f, DARK)
        for (z in floatArrayOf(1.2f, -1.2f)) Ps1Geo.box(mb, 3.95f, 1.2f + lift, z, 0.12f, 0.28f, 0.5f, HEAD)
        for (z in floatArrayOf(1.25f, -1.25f)) Ps1Geo.box(mb, -3.3f, 1.2f + lift, z, 0.1f, 0.26f, 0.4f, TAIL)
        Ps1Geo.wheel(mb, -3.55f, 1.15f + lift, 0f, 0.62f, 0.3f, TYRE, rim, rotY = Math.PI.toFloat() / 2f)
        for (x in floatArrayOf(-2.2f, 2.3f)) for (z in floatArrayOf(1.64f, -1.64f)) Ps1Geo.wheel(mb, x, 0f, z, 0.68f, 0.56f, TYRE, rim)
    }

    private fun bike(mb: MeshBuilder, paint: Surf, rim: Surf, dy: Float) {
        // Tank and tail in paint, seat dark.
        Ps1Geo.extrude(mb, BIKE_TANK, 0.62f, 0.8f, paint, taperY = 0.95f, dy = dy)
        Ps1Geo.extrude(mb, BIKE_TAIL, 0.46f, 0.8f, paint, taperY = 1.0f, dy = dy)
        Ps1Geo.extrude(mb, BIKE_SEAT, 0.5f, 0.9f, Surf.srgb(0x15171C), taperY = 5f, dy = dy)
        // Engine, swing arm and exhaust.
        Ps1Geo.box(mb, 0.1f, 0.5f + dy, 0f, 1.2f, 0.7f, 0.56f, TRIM)
        Ps1Geo.box(mb, -0.2f, 0.9f + dy, 0f, 0.7f, 0.2f, 0.36f, TRIM)
        for (z in floatArrayOf(0.25f, -0.25f)) Ps1Geo.box(mb, -0.85f, 0.34f + dy * 0.5f, z, 1.9f, 0.1f, 0.08f, TRIM, rotZ = 0.12f)
        for (z in floatArrayOf(0.34f, -0.34f)) Ps1Geo.box(mb, -0.9f, 0.3f + dy, z, 1.7f, 0.15f, 0.14f, Surf.srgb(0xB8BCC2), rotZ = 0.08f)
        // Forks from the hub up to the bars, leaning back.
        for (z in floatArrayOf(0.2f, -0.2f)) Ps1Geo.box(mb, 1.3f, 0.6f + dy * 0.5f, z, 0.1f, 1.3f, 0.09f, Surf.srgb(0xB8BCC2), rotZ = 0.4f)
        Ps1Geo.box(mb, 1.02f, 1.28f + dy, 0f, 0.1f, 0.1f, 1.2f, DARK)
        Ps1Geo.box(mb, 1.22f, 1.0f + dy, 0f, 0.14f, 0.28f, 0.28f, HEAD)
        Ps1Geo.box(mb, -2.1f, 1.0f + dy, 0f, 0.1f, 0.14f, 0.3f, TAIL)
        Ps1Geo.wheel(mb, -1.5f, 0f, 0f, 0.62f, 0.24f, TYRE, rim)
        Ps1Geo.wheel(mb, 1.55f, 0f, 0f, 0.62f, 0.24f, TYRE, rim)
    }
}
