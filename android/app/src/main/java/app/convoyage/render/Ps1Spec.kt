package app.convoyage.render

import java.security.MessageDigest

enum class Ps1Kind(val wire: String) {
    Car("car"), Ute("ute"), Fourwd("4wd"), Motorcycle("motorcycle");

    companion object {
        fun of(wire: String?): Ps1Kind = entries.firstOrNull { it.wire == wire } ?: Car
    }
}

/** Body styles, each belonging to one kind. [wire] matches the server's `build.body`. */
enum class Ps1Body(val wire: String, val kind: Ps1Kind, val label: String) {
    Sedan("sedan", Ps1Kind.Car, "Sedan"),
    Hatch("hatch", Ps1Kind.Car, "Hatch"),
    Wagon("wagon", Ps1Kind.Car, "Wagon"),
    Coupe("coupe", Ps1Kind.Car, "Coupe"),
    Suv("suv", Ps1Kind.Car, "SUV"),
    Van("van", Ps1Kind.Car, "Van"),
    HotHatch("hot_hatch", Ps1Kind.Car, "Hot hatch"),
    Muscle("muscle", Ps1Kind.Car, "Muscle"),
    Roadster("roadster", Ps1Kind.Car, "Roadster"),
    SingleCab("single_cab", Ps1Kind.Ute, "Single cab"),
    DualCab("dual_cab", Ps1Kind.Ute, "Dual cab"),
    FourwdWagon("4wd_wagon", Ps1Kind.Fourwd, "Wagon"),
    FourwdUte("4wd_ute", Ps1Kind.Fourwd, "Dual cab ute"),
    Naked("naked", Ps1Kind.Motorcycle, "Naked"),
    SportBike("sport_bike", Ps1Kind.Motorcycle, "Sport"),
    Cruiser("cruiser", Ps1Kind.Motorcycle, "Cruiser"),
    Adventure("adventure", Ps1Kind.Motorcycle, "Adventure");

    companion object {
        fun defaultFor(kind: Ps1Kind): Ps1Body = when (kind) {
            Ps1Kind.Car -> Sedan
            Ps1Kind.Ute -> DualCab
            Ps1Kind.Fourwd -> FourwdWagon
            Ps1Kind.Motorcycle -> Naked
        }

        fun forKind(kind: Ps1Kind): List<Ps1Body> = entries.filter { it.kind == kind }

        /** The body for [wire] if it belongs to [kind], else the kind's default. */
        fun of(wire: String?, kind: Ps1Kind): Ps1Body = entries.firstOrNull { it.wire == wire && it.kind == kind } ?: defaultFor(kind)
    }
}

enum class Ps1Finish(val wire: String) {
    Gloss("gloss"), Satin("satin"), Matte("matte"), Metallic("metallic"), Pearl("pearl");

    companion object {
        fun of(wire: String?): Ps1Finish = entries.firstOrNull { it.wire == wire } ?: Gloss
    }
}

/** How a wheel looks: a spoke count, how much of the face is rim lip, and the colour the white texture is tinted to. */
data class Ps1Wheel(val id: String, val spokes: Int, val lip: Float, val solid: Boolean, val rgb: Int)

object Ps1Wheels {
    const val DEFAULT = "stock"

    /** Six looks, keyed by the server's catalogue ids. */
    val ALL = listOf(
        Ps1Wheel("stock", spokes = 5, lip = 0.80f, solid = false, rgb = 0xB8BCC2),
        Ps1Wheel("sport5", spokes = 5, lip = 0.84f, solid = false, rgb = 0xC08A3E),
        Ps1Wheel("mesh", spokes = 12, lip = 0.86f, solid = false, rgb = 0x9AA3B0),
        Ps1Wheel("split_spoke", spokes = 10, lip = 0.84f, solid = false, rgb = 0xE9EDF2),
        Ps1Wheel("deep_dish", spokes = 6, lip = 0.55f, solid = false, rgb = 0xF4F1EA),
        Ps1Wheel("steelie", spokes = 0, lip = 0.86f, solid = true, rgb = 0x7C838E),
    )

    /** Ids the first version of the app used before the server had a catalogue. */
    private val LEGACY = mapOf("multi_spoke" to "mesh", "split_five" to "split_spoke", "forged_bronze" to "sport5")

    fun of(id: String?): Ps1Wheel {
        val key = LEGACY[id] ?: id
        return ALL.firstOrNull { it.id == key } ?: ALL[0]
    }
}

/** Everything about a vehicle that changes how it renders. Decals are not drawn, so they are not part of it. */
data class Ps1Spec(
    val kind: Ps1Kind = Ps1Kind.Car,
    /** 0xRRGGBB. */
    val paint: Int = DEFAULT_PAINT,
    val finish: Ps1Finish = Ps1Finish.Gloss,
    val wheel: Ps1Wheel = Ps1Wheels.ALL[0],
    /** Millimetres from stock, -200..200. */
    val rideHeightMm: Int = 0,
    /** Always one of [kind]'s bodies. */
    val body: Ps1Body = Ps1Body.defaultFor(kind),
) {
    /**
     * A stable cache key for one render of this build: the same visible build always gives the same key (case and
     * out-of-range values are normalised away) and any visible change gives another. [variant] separates thumbnail sizes.
     */
    fun key(variant: String = ""): String {
        val canonical = "v$RENDERER_VERSION|${kind.wire}|${body.wire}|%06x|${finish.wire}|${wheel.id}|$rideHeightMm|$variant".format(paint and 0xFFFFFF)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_PAINT = 0xB8BCC2

        /** Bump to throw every cached thumbnail away when the models or shaders change. */
        const val RENDERER_VERSION = 3

        /** `#RRGGBB` (any case) or null/garbage, which gives the default paint. */
        fun parseHex(hex: String?): Int {
            val h = hex?.trim()?.removePrefix("#") ?: return DEFAULT_PAINT
            if (h.length != 6) return DEFAULT_PAINT
            return h.toIntOrNull(16) ?: DEFAULT_PAINT
        }

        fun of(kind: String?, paint: String?, finish: String?, wheel: String?, rideHeightMm: Int?, body: String? = null): Ps1Spec = Ps1Spec(
            kind = Ps1Kind.of(kind),
            body = Ps1Body.of(body, Ps1Kind.of(kind)),
            paint = parseHex(paint),
            finish = Ps1Finish.of(finish),
            wheel = Ps1Wheels.of(wheel),
            rideHeightMm = (rideHeightMm ?: 0).coerceIn(-200, 200),
        )
    }
}
