package app.convoyage.render

/** The front of a vehicle: grille and headlight style. Generic shapes, never badges or logos. */
enum class Ps1Face {
    /** Two lights and a slot grille. */
    Generic,
    /** Two small kidney grilles. */
    Kidney,
    /** Two tall kidney grilles reaching down the bumper. */
    KidneyTall,
    /** One big, wide lower mouth. */
    Wide,
    /** One tall six-sided grille from the bonnet to the bumper. */
    Singleframe,
    /** Seven vertical slots between round lights. */
    SevenSlot,
    /** Round headlights. */
    Round,
    /** Tall C-shaped lights either side of a big grille. */
    CClamp,
    /** A light strip across the whole nose. */
    LightBar,
    /** Thin lights and a low intake, no real grille. */
    Slim,
}

/** Bits a body carries beyond its shell. */
enum class Ps1Kit {
    RoofRails, RoofRack, RoofSpoiler, Wing, BootLip, Ducktail, Scoop, Bulge, Stripes,
    RooBar, Snorkel, Spare, SportsBar, Canopy, Flares, Steps, RearBar, TriTail,
}

/**
 * Where things sit along a body, as fractions: heights of the overall height, positions of the overall length from the
 * nose. Each body has one; catalogue models adjust it.
 */
data class Ps1Tpl(
    /** Share of the overhangs (length minus wheelbase) that is at the front. */
    val frontShare: Float,
    /** Bottom of the body side. */
    val sill: Float,
    /** Top of the bonnet at the nose. */
    val nose: Float,
    /** Window line. */
    val belt: Float,
    /** Top of the boot, tailgate or tray at the back. */
    val deck: Float,
    /** Foot of the windscreen. */
    val ws: Float,
    /** Front and back of the flat roof. */
    val roofF: Float,
    val roofR: Float,
    /** Foot of the rear glass; 1 for a vertical tailgate. */
    val rw: Float,
    /** Utes: the back of the cab (the tray runs from here to the tail). 0 = no tray. */
    val cab: Float = 0f,
    val doors: Int = 4,
    /** A pillar behind the rear doors and a quarter window (wagons, SUVs, people movers). */
    val longRoof: Boolean = false,
    /** Panels instead of side glass behind the front doors (vans). */
    val blind: Boolean = false,
    /** No roof (roadsters, convertibles). */
    val open: Boolean = false,
    /** A flat tray deck instead of a tub (cab-chassis utes). */
    val flatDeck: Boolean = false,
)

/** One vehicle's shape in metres: real dimensions plus where its glass, roof and tray are. */
class Ps1Shape(
    /** Catalogue id, or `generic-<body>`. */
    val id: String,
    val body: Ps1Body,
    val len: Float, val wid: Float, val hgt: Float, val wb: Float,
    /** Overall tyre diameter and tread width. */
    val tyre: Float, val tyreW: Float,
    val tpl: Ps1Tpl,
    val face: Ps1Face,
    val kit: Set<Ps1Kit>,
) {
    val fo: Float get() = (len - wb) * tpl.frontShare
    val sill: Float get() = tpl.sill * hgt
    val nose: Float get() = tpl.nose * hgt
    val belt: Float get() = tpl.belt * hgt
    val deck: Float get() = tpl.deck * hgt
    val ws: Float get() = tpl.ws * len
    val roofF: Float get() = tpl.roofF * len
    val roofR: Float get() = tpl.roofR * len
    val rw: Float get() = tpl.rw * len
    val cab: Float get() = tpl.cab * len

    companion object {
        /** `225/45R17` → overall diameter and tread width in metres. */
        fun tyre(code: String): Pair<Float, Float> {
            val m = Regex("""(\d{3})/(\d{2})\s*Z?R\s*(\d{2})""").find(code.uppercase()) ?: return Pair(0.65f, 0.225f)
            val w = m.groupValues[1].toFloat(); val ar = m.groupValues[2].toFloat(); val rim = m.groupValues[3].toFloat()
            return Pair((rim * 25.4f + 2f * w * ar / 100f) / 1000f, w / 1000f)
        }

        fun of(id: String, body: Ps1Body, mm: IntArray, tyre: String, tpl: Ps1Tpl, face: Ps1Face, kit: Set<Ps1Kit>): Ps1Shape {
            val (d, w) = tyre(tyre)
            return Ps1Shape(id, body, mm[0] / 1000f, mm[1] / 1000f, mm[2] / 1000f, mm[3] / 1000f, d, w, tpl, face, kit)
        }
    }
}

/** Every four-wheeled body's proportions, taken from a typical car of that body, and the generic shapes built on them. */
object Ps1Shapes {
    fun tpl(body: Ps1Body): Ps1Tpl = when (body) {
        // Corolla sedan
        Ps1Body.Sedan -> Ps1Tpl(0.49f, 0.13f, 0.50f, 0.66f, 0.68f, ws = 0.30f, roofF = 0.45f, roofR = 0.73f, rw = 0.855f)
        // i30 hatch: the roof ends early and the glass slopes down to a short tail.
        Ps1Body.Hatch -> Ps1Tpl(0.53f, 0.13f, 0.50f, 0.65f, 0.70f, ws = 0.29f, roofF = 0.44f, roofR = 0.80f, rw = 0.965f)
        Ps1Body.HotHatch -> Ps1Tpl(0.53f, 0.12f, 0.49f, 0.65f, 0.70f, ws = 0.29f, roofF = 0.44f, roofR = 0.80f, rw = 0.965f)
        // Golf wagon: the roof runs to the tail, glass almost upright.
        Ps1Body.Wagon -> Ps1Tpl(0.46f, 0.13f, 0.50f, 0.64f, 0.66f, ws = 0.28f, roofF = 0.42f, roofR = 0.955f, rw = 1f, longRoof = true)
        // Stinger: a long sloping hatch.
        Ps1Body.Liftback -> Ps1Tpl(0.48f, 0.13f, 0.50f, 0.68f, 0.70f, ws = 0.29f, roofF = 0.44f, roofR = 0.66f, rw = 0.95f)
        // GR86
        Ps1Body.Coupe -> Ps1Tpl(0.50f, 0.12f, 0.48f, 0.67f, 0.70f, ws = 0.31f, roofF = 0.46f, roofR = 0.66f, rw = 0.86f, doors = 2)
        Ps1Body.Convertible -> Ps1Tpl(0.50f, 0.12f, 0.50f, 0.68f, 0.70f, ws = 0.33f, roofF = 0.42f, roofR = 0.42f, rw = 0.86f, doors = 2, open = true)
        // MX-5
        Ps1Body.Roadster -> Ps1Tpl(0.46f, 0.12f, 0.52f, 0.70f, 0.72f, ws = 0.38f, roofF = 0.45f, roofR = 0.45f, rw = 0.86f, doors = 2, open = true)
        // Mustang fastback
        Ps1Body.Muscle -> Ps1Tpl(0.50f, 0.12f, 0.56f, 0.70f, 0.72f, ws = 0.37f, roofF = 0.50f, roofR = 0.66f, rw = 0.88f, doors = 2)
        // RAV4
        Ps1Body.Suv -> Ps1Tpl(0.52f, 0.20f, 0.58f, 0.65f, 0.68f, ws = 0.27f, roofF = 0.40f, roofR = 0.93f, rw = 0.985f, longRoof = true)
        // Carnival
        Ps1Body.PeopleMover -> Ps1Tpl(0.50f, 0.15f, 0.55f, 0.60f, 0.62f, ws = 0.19f, roofF = 0.33f, roofR = 0.96f, rw = 1f, longRoof = true)
        // HiAce LWB: cab over the front wheels, panel sides.
        Ps1Body.Van -> Ps1Tpl(0.44f, 0.10f, 0.45f, 0.52f, 0.52f, ws = 0.05f, roofF = 0.16f, roofR = 0.99f, rw = 1f, doors = 2, blind = true)
        // Car-based ute (Commodore, Falcon)
        Ps1Body.SingleCab -> Ps1Tpl(0.46f, 0.12f, 0.50f, 0.66f, 0.66f, ws = 0.30f, roofF = 0.43f, roofR = 0.55f, rw = 1f, cab = 0.585f, doors = 2)
        // Hilux dual cab: tub behind a four-door cab.
        Ps1Body.DualCab -> Ps1Tpl(0.43f, 0.19f, 0.59f, 0.66f, 0.63f, ws = 0.25f, roofF = 0.36f, roofR = 0.655f, rw = 1f, cab = 0.68f)
        Ps1Body.ExtraCab -> Ps1Tpl(0.43f, 0.19f, 0.59f, 0.66f, 0.63f, ws = 0.25f, roofF = 0.36f, roofR = 0.57f, rw = 1f, cab = 0.60f, doors = 2)
        Ps1Body.CabChassis -> Ps1Tpl(0.43f, 0.19f, 0.59f, 0.66f, 0.55f, ws = 0.25f, roofF = 0.36f, roofR = 0.50f, rw = 1f, cab = 0.53f, doors = 2, flatDeck = true)
        // LandCruiser 300
        Ps1Body.FourwdWagon -> Ps1Tpl(0.43f, 0.24f, 0.60f, 0.66f, 0.64f, ws = 0.27f, roofF = 0.38f, roofR = 0.97f, rw = 1f, longRoof = true)
        Ps1Body.FourwdUte -> Ps1Tpl(0.43f, 0.22f, 0.60f, 0.66f, 0.63f, ws = 0.25f, roofF = 0.36f, roofR = 0.655f, rw = 1f, cab = 0.68f)
        // LandCruiser 79 single cab: long bonnet, short cab, flat tray.
        Ps1Body.FourwdCabChassis -> Ps1Tpl(0.40f, 0.24f, 0.60f, 0.64f, 0.55f, ws = 0.24f, roofF = 0.33f, roofR = 0.45f, rw = 1f, cab = 0.48f, doors = 2, flatDeck = true)
        else -> Ps1Tpl(0.5f, 0.13f, 0.5f, 0.66f, 0.68f, 0.3f, 0.45f, 0.73f, 0.855f)
    }

    /** Typical dimensions (L, W, H, WB in mm), tyre, face and kit of each body when no model is chosen. */
    private fun typical(body: Ps1Body): Triple<IntArray, String, Pair<Ps1Face, Set<Ps1Kit>>> = when (body) {
        Ps1Body.Sedan -> Triple(intArrayOf(4630, 1780, 1435, 2700), "225/45R17", Ps1Face.Generic to emptySet())
        Ps1Body.Hatch -> Triple(intArrayOf(4340, 1795, 1455, 2650), "225/45R17", Ps1Face.Generic to emptySet())
        Ps1Body.HotHatch -> Triple(intArrayOf(4340, 1800, 1440, 2650), "235/35R19", Ps1Face.Wide to setOf(Ps1Kit.RoofSpoiler))
        Ps1Body.Wagon -> Triple(intArrayOf(4630, 1790, 1460, 2670), "225/45R17", Ps1Face.Generic to setOf(Ps1Kit.RoofRails))
        Ps1Body.Liftback -> Triple(intArrayOf(4830, 1870, 1400, 2905), "225/40R19", Ps1Face.Generic to emptySet())
        Ps1Body.Coupe -> Triple(intArrayOf(4265, 1775, 1310, 2575), "215/40R18", Ps1Face.Slim to setOf(Ps1Kit.BootLip))
        Ps1Body.Convertible -> Triple(intArrayOf(4500, 1820, 1390, 2700), "225/45R18", Ps1Face.Generic to emptySet())
        Ps1Body.Roadster -> Triple(intArrayOf(3915, 1735, 1225, 2310), "205/45R17", Ps1Face.Slim to emptySet())
        Ps1Body.Muscle -> Triple(intArrayOf(4784, 1916, 1381, 2720), "255/40R19", Ps1Face.Wide to setOf(Ps1Kit.Bulge, Ps1Kit.Stripes, Ps1Kit.TriTail))
        Ps1Body.Suv -> Triple(intArrayOf(4600, 1855, 1685, 2690), "225/60R18", Ps1Face.Generic to setOf(Ps1Kit.RoofRails))
        Ps1Body.PeopleMover -> Triple(intArrayOf(5155, 1995, 1775, 3090), "235/55R19", Ps1Face.Wide to emptySet())
        Ps1Body.Van -> Triple(intArrayOf(5265, 1950, 1990, 3210), "215/70R16", Ps1Face.Generic to emptySet())
        Ps1Body.SingleCab -> Triple(intArrayOf(5040, 1899, 1494, 3009), "245/40R19", Ps1Face.Wide to emptySet())
        Ps1Body.DualCab -> Triple(intArrayOf(5325, 1855, 1815, 3085), "265/60R18", Ps1Face.Wide to emptySet())
        Ps1Body.ExtraCab -> Triple(intArrayOf(5330, 1855, 1810, 3085), "265/65R17", Ps1Face.Wide to emptySet())
        Ps1Body.CabChassis -> Triple(intArrayOf(5330, 1855, 1800, 3085), "265/65R17", Ps1Face.Generic to emptySet())
        Ps1Body.FourwdWagon -> Triple(intArrayOf(4980, 1980, 1950, 2850), "265/70R17", Ps1Face.Generic to setOf(Ps1Kit.RooBar, Ps1Kit.Snorkel, Ps1Kit.RoofRack, Ps1Kit.Spare, Ps1Kit.Flares))
        Ps1Body.FourwdUte -> Triple(intArrayOf(5370, 1918, 1884, 3270), "265/70R17", Ps1Face.Generic to setOf(Ps1Kit.RooBar, Ps1Kit.Snorkel, Ps1Kit.SportsBar, Ps1Kit.Flares))
        Ps1Body.FourwdCabChassis -> Triple(intArrayOf(5220, 1870, 1955, 3180), "265/70R16", Ps1Face.Generic to setOf(Ps1Kit.Snorkel, Ps1Kit.RooBar))
        else -> Triple(intArrayOf(4630, 1780, 1435, 2700), "225/45R17", Ps1Face.Generic to emptySet())
    }

    private val generic = HashMap<Ps1Body, Ps1Shape>()

    /** The shape of a body with no model chosen. Null for motorcycles. */
    fun generic(body: Ps1Body): Ps1Shape? {
        if (body.kind == Ps1Kind.Motorcycle) return null
        return synchronized(generic) {
            generic.getOrPut(body) {
                val (mm, tyre, look) = typical(body)
                Ps1Shape.of("generic-${body.wire}", body, mm, tyre, tpl(body), look.first, look.second)
            }
        }
    }
}
