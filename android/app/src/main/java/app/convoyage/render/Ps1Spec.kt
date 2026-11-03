package app.convoyage.render

import java.security.MessageDigest

enum class Ps1Kind(val wire: String) {
    Car("car"), Ute("ute"), Fourwd("4wd"), Motorcycle("motorcycle");

    companion object {
        fun of(wire: String?): Ps1Kind = entries.firstOrNull { it.wire == wire } ?: Car
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
) {
    /**
     * A stable cache key for one render of this build: the same visible build always gives the same key (case and
     * out-of-range values are normalised away) and any visible change gives another. [variant] separates thumbnail sizes.
     */
    fun key(variant: String = ""): String {
        val canonical = "v$RENDERER_VERSION|${kind.wire}|%06x|${finish.wire}|${wheel.id}|$rideHeightMm|$variant".format(paint and 0xFFFFFF)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_PAINT = 0xB8BCC2

        /** Bump to throw every cached thumbnail away when the models or shaders change. */
        const val RENDERER_VERSION = 2

        /** `#RRGGBB` (any case) or null/garbage, which gives the default paint. */
        fun parseHex(hex: String?): Int {
            val h = hex?.trim()?.removePrefix("#") ?: return DEFAULT_PAINT
            if (h.length != 6) return DEFAULT_PAINT
            return h.toIntOrNull(16) ?: DEFAULT_PAINT
        }

        fun of(kind: String?, paint: String?, finish: String?, wheel: String?, rideHeightMm: Int?): Ps1Spec = Ps1Spec(
            kind = Ps1Kind.of(kind),
            paint = parseHex(paint),
            finish = Ps1Finish.of(finish),
            wheel = Ps1Wheels.of(wheel),
            rideHeightMm = (rideHeightMm ?: 0).coerceIn(-200, 200),
        )
    }
}
