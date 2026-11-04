package app.convoyage.render

/** A point on a profile curve: [d] metres back from the nose, [v] a height or half width in metres. */
class Pt(val d: Float, val v: Float)

/**
 * A vehicle's body as curves, Gran Turismo style: what it looks like from the side, from above, and where its glass is.
 * All in metres from the front bumper and the ground.
 *
 * [top] is the centreline from the nose over the roof to the tail (or the back of a ute's cab). The cabin markers are
 * [cowl] (foot of the windscreen), [roofF] and [roofR] (ends of the roof) and [glassEnd] (foot of the rear glass).
 * [bot] is the underside, [plan] the half width at the widest line, [belt] the side window line in the cabin, and
 * [windows] the side glass between pillars.
 */
class Ps1Profile(
    val top: List<Pt>,
    val bot: List<Pt>,
    val plan: List<Pt>,
    val belt: List<Pt>,
    val cowl: Float, val roofF: Float, val roofR: Float, val glassEnd: Float,
    val windows: List<ClosedFloatingPointRange<Float>>,
    /** Roof half width as a share of the body's. */
    val tumble: Float,
) {
    /** Back of the shell: the tail, or the back of a ute's cab. */
    val end: Float get() = top.last().d
    val hasCabin: Boolean get() = roofR > roofF

    fun topAt(d: Float) = at(top, d)
    fun botAt(d: Float) = at(bot, d)
    fun wAt(d: Float) = at(plan, d)
    fun beltAt(d: Float) = if (belt.isEmpty()) topAt(d) else at(belt, d)

    /** The same body stretched to other dimensions (a sibling model, or a variant a few centimetres off). */
    fun scaled(len: Float, wid: Float, hgt: Float): Ps1Profile {
        val sl = len / lenOf(); val sw = wid / 2f / plan.maxOf { it.v }; val sh = hgt / top.maxOf { it.v }
        fun l(p: List<Pt>, sv: Float) = p.map { Pt(it.d * sl, it.v * sv) }
        return Ps1Profile(
            l(top, sh), l(bot, sh), l(plan, sw), l(belt, sh), cowl * sl, roofF * sl, roofR * sl, glassEnd * sl,
            windows.map { (it.start * sl)..(it.endInclusive * sl) }, tumble,
        )
    }

    /** The authored overall length: the tail, or for a ute the furthest the body curves reach. */
    private fun lenOf(): Float = maxOf(top.last().d, bot.last().d, plan.last().d)

    companion object {
        fun at(p: List<Pt>, d: Float): Float {
            if (d <= p.first().d) return p.first().v
            for (i in 1 until p.size) if (d <= p[i].d) {
                val a = p[i - 1]; val b = p[i]
                val t = if (b.d - a.d < 1e-5f) 1f else (d - a.d) / (b.d - a.d)
                return a.v + (b.v - a.v) * t
            }
            return p.last().v
        }

        /** "0:.5 1.4c:.97 2.2f:1.43": points, with c/f/r/b marking cowl, roof front, roof rear and glass end. */
        private fun parse(s: String): Pair<List<Pt>, Map<Char, Float>> {
            val marks = HashMap<Char, Float>()
            val pts = s.trim().split(Regex("\\s+")).map { tok ->
                val (dp, v) = tok.split(':')
                val mark = dp.lastOrNull()?.takeIf { it.isLetter() }
                val d = (if (mark != null) dp.dropLast(1) else dp).toFloat()
                if (mark != null) marks[mark] = d
                Pt(d, v.toFloat())
            }
            return Pair(pts, marks)
        }

        /** A hand-authored profile; see the class for the curves. */
        fun of(top: String, bot: String, plan: String, belt: String, win: String, tumble: Float = 0.75f): Ps1Profile {
            val (t, m) = parse(top)
            val c = m['c'] ?: t[0].d
            val f = m['f'] ?: c
            val r = m['r'] ?: f
            val b = m['b'] ?: r
            val wins = win.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map {
                val (a, z) = it.split('-'); a.toFloat()..z.toFloat()
            }
            return Ps1Profile(t, parse(bot).first, parse(plan).first, parse(belt).first, c, f, r, b, wins, tumble)
        }

        /** The profile of a shape with no hand-made one, from its body template and real dimensions. */
        fun from(sh: Ps1Shape): Ps1Profile {
            val t = sh.tpl; val L = sh.len; val H = sh.hgt; val hw = sh.wid / 2f
            val sill = sh.sill; val nose = sh.nose; val belt = sh.belt; val deck = sh.deck
            val ws = sh.ws; val rf = sh.roofF
            val end = if (t.cab > 0f) sh.cab else L
            val rr = minOf(sh.roofR, end - 0.05f)
            val top = ArrayList<Pt>()
            top += Pt(0f, nose - 0.15f); top += Pt(0.16f, nose - 0.03f); top += Pt(ws * 0.55f, nose + (belt - nose) * 0.6f); top += Pt(ws, belt)
            var gEnd: Float
            if (t.open) {
                top += Pt(L - 0.14f, deck); top += Pt(L, deck - 0.09f)
                return Ps1Profile(
                    top, bottom(sill, L), plan(sh, hw, L), emptyList(), ws, ws, ws, ws, emptyList(), 0.75f,
                )
            }
            top += Pt(rf, H); top += Pt((rf + rr) / 2f, H); top += Pt(rr, H - 0.03f)
            when {
                t.cab > 0f -> { gEnd = end; top += Pt(end, belt + 0.06f) }
                t.rw >= 0.995f -> { gEnd = L - 0.06f; top += Pt(gEnd, belt + 0.03f); top += Pt(L, deck - 0.12f) }
                L - sh.rw < 0.3f -> { gEnd = sh.rw; top += Pt(gEnd, deck + 0.03f); top += Pt(L, deck - 0.12f) }
                else -> { gEnd = sh.rw; top += Pt(gEnd, deck + 0.02f); top += Pt(L - 0.14f, deck); top += Pt(L, deck - 0.09f) }
            }
            val wins = ArrayList<ClosedFloatingPointRange<Float>>()
            when {
                t.blind -> wins += (rf + 0.08f)..(rf + 0.9f)
                t.doors >= 4 -> {
                    val pb = rf + (rr - rf) * (if (t.longRoof) 0.40f else 0.50f)
                    wins += (rf + 0.1f)..(pb - 0.05f)
                    if (t.longRoof) {
                        val pc = rf + (rr - rf) * 0.78f
                        wins += (pb + 0.05f)..(pc - 0.05f); wins += (pc + 0.05f)..(rr - 0.06f)
                    } else wins += (pb + 0.05f)..(rr - 0.1f)
                }
                else -> wins += (rf + 0.1f)..(rr - (if (t.cab > 0f) 0.06f else 0.15f))
            }
            return Ps1Profile(
                top, bottom(sill, end), plan(sh, hw, L), listOf(Pt(ws, belt), Pt(L, belt + 0.04f)),
                ws, rf, rr, gEnd, wins, 0.76f,
            )
        }

        private fun bottom(sill: Float, end: Float) =
            listOf(Pt(0f, sill + 0.18f), Pt(0.16f, sill + 0.03f), Pt(end - 0.16f, sill + 0.03f), Pt(end, sill + 0.16f))

        private fun plan(sh: Ps1Shape, hw: Float, L: Float) = listOf(
            Pt(0f, hw * 0.84f), Pt(0.15f, hw * 0.95f), Pt(sh.fo, hw), Pt(sh.fo + sh.wb / 2f, hw * 0.985f),
            Pt(sh.fo + sh.wb, hw), Pt(L - 0.15f, hw * 0.95f), Pt(L, hw * 0.86f),
        )
    }
}

/**
 * Hand-made bodies for catalogue models, authored at each car's real dimensions. A sibling model can borrow one (it is
 * stretched to its own length, width and height).
 */
object Ps1Profiles {
    private val focusRs = Ps1Profile.of(
        top = "0:.48 .06:.62 .3:.76 .8:.86 1.42c:.97 2.2f:1.43 2.75:1.472 3.5r:1.41 4.12b:1.06 4.3:1.03 4.39:.84",
        bot = "0:.2 .25:.14 4.15:.16 4.39:.36",
        plan = "0:.62 .2:.82 .55:.88 .88:.915 1.3:.9 2.6:.895 3.53:.915 4.05:.88 4.3:.78 4.39:.68",
        belt = "1.42:.97 2.3:1.0 3.55:1.07", win = "1.65-2.55 2.65-3.3 3.36-3.6", tumble = 0.74f,
    )
    private val i30 = Ps1Profile.of(
        top = "0:.5 .06:.63 .3:.75 .8:.85 1.4c:.96 2.2f:1.42 2.8:1.455 3.48r:1.39 4.1b:1.05 4.26:1.02 4.34:.84",
        bot = "0:.24 .25:.16 4.1:.17 4.34:.38",
        plan = "0:.6 .2:.8 .6:.87 .88:.8975 2.6:.89 3.53:.8975 4.05:.87 4.3:.76 4.34:.66",
        belt = "1.4:.96 2.3:.99 3.5:1.05", win = "1.62-2.5 2.6-3.25 3.32-3.55", tumble = 0.75f,
    )
    private val golf = Ps1Profile.of(
        top = "0:.5 .06:.63 .3:.76 .8:.86 1.38c:.98 2.15f:1.44 2.8:1.478 3.6r:1.42 4.1b:1.07 4.22:1.03 4.289:.85",
        bot = "0:.2 .25:.14 4.1:.16 4.289:.36",
        plan = "0:.62 .2:.82 .6:.875 .85:.8945 2.6:.885 3.48:.8945 4:.87 4.289:.72",
        belt = "1.38:.98 3.6:1.05", win = "1.6-2.5 2.6-3.25 3.32-3.62", tumble = 0.76f,
    )
    private val grYaris = Ps1Profile.of(
        top = "0:.5 .06:.62 .25:.73 .7:.82 1.2c:.95 1.95f:1.42 2.4:1.455 2.95r:1.38 3.75b:1.04 3.9:1 3.995:.82",
        bot = "0:.18 .25:.13 3.8:.16 3.995:.36",
        plan = "0:.6 .2:.82 .55:.88 .75:.9025 1.2:.87 2.4:.86 3.31:.9025 3.8:.85 3.995:.72",
        belt = "1.2:.95 3:1.05", win = "1.45-2.75", tumble = 0.7f,
    )
    private val bmwG80 = Ps1Profile.of(
        top = "0:.52 .05:.63 .25:.74 .9:.83 1.65c:.96 2.45f:1.4 3:1.433 3.6r:1.39 4.18b:1.02 4.45:1 4.7:.97 4.794:.8",
        bot = "0:.17 .3:.13 4.5:.15 4.794:.36",
        plan = "0:.66 .2:.86 .55:.92 .85:.95 1.4:.93 2.6:.92 3.7:.95 4.3:.93 4.65:.84 4.794:.74",
        belt = "1.65:.96 2.6:.98 3.8:1.02", win = "1.9-2.85 2.95-3.55 3.58-3.75", tumble = 0.72f,
    )
    private val bmwE46 = Ps1Profile.of(
        top = "0:.5 .08:.62 .3:.72 .9:.8 1.5c:.92 2.25f:1.33 2.8:1.372 3.35r:1.33 3.85b:.98 4.35:.96 4.492:.78",
        bot = "0:.18 .3:.13 4.2:.15 4.492:.34",
        plan = "0:.62 .2:.82 .55:.87 .85:.89 1.3:.88 2.6:.875 3.6:.89 4.2:.86 4.492:.7",
        belt = "1.5:.92 3.4:.98", win = "1.75-2.9 2.95-3.25", tumble = 0.74f,
    )
    private val wrx = Ps1Profile.of(
        top = "0:.52 .06:.64 .3:.75 .9:.85 1.5c:.97 2.25f:1.43 2.8:1.465 3.35r:1.42 4.05b:1.03 4.5:1.01 4.67:.84",
        bot = "0:.2 .3:.15 4.4:.16 4.67:.38",
        plan = "0:.64 .2:.84 .6:.9 .95:.9125 1.4:.89 2.6:.885 3.62:.9125 4.2:.88 4.67:.74",
        belt = "1.5:.97 3.4:1.02", win = "1.75-2.65 2.75-3.35", tumble = 0.73f,
    )
    private val mustang = Ps1Profile.of(
        top = "0:.6 .05:.72 .3:.82 1.1:.9 1.9c:.98 2.55f:1.35 2.95:1.381 3.3r:1.33 4.2b:1 4.6:.99 4.784:.85",
        bot = "0:.18 .3:.14 4.5:.16 4.784:.36",
        plan = "0:.7 .25:.88 .6:.94 .95:.958 1.5:.93 2.8:.93 3.67:.958 4.3:.93 4.784:.8",
        belt = "1.9:.98 3.3:1.03", win = "2.15-3.2", tumble = 0.7f,
    )
    private val commodore = Ps1Profile.of(
        top = "0:.55 .06:.67 .3:.77 1:.86 1.6c:.98 2.4f:1.43 3:1.471 3.65r:1.42 4.35b:1.05 4.8:1.03 4.964:.86",
        bot = "0:.2 .3:.15 4.7:.17 4.964:.38",
        plan = "0:.66 .2:.86 .6:.93 .95:.949 1.5:.93 2.8:.925 3.87:.949 4.5:.92 4.964:.78",
        belt = "1.6:.98 3.7:1.03", win = "1.85-2.85 2.95-3.6", tumble = 0.73f,
    )
    private val patrolY62 = Ps1Profile.of(
        top = "0:.78 .08:.95 .3:1.07 1:1.15 1.55c:1.24 2.25f:1.86 3:1.94 4.95r:1.9 5.07b:1.3 5.12:1.22 5.165:1.05",
        bot = "0:.42 .35:.38 4.8:.42 5.165:.55",
        plan = "0:.8 .2:.95 .95:.9975 2.5:.99 4.03:.9975 5:.97 5.165:.9",
        belt = "1.55:1.24 2.6:1.28 5:1.33", win = "1.85-2.85 2.95-3.85 3.95-4.85", tumble = 0.8f,
    )
    private val lc300 = Ps1Profile.of(
        top = "0:.82 .08:.98 .3:1.1 1:1.17 1.5c:1.26 2.15f:1.85 2.9:1.95 4.8r:1.92 4.92b:1.32 4.96:1.25 4.98:1.05",
        bot = "0:.45 .35:.4 4.7:.45 4.98:.55",
        plan = "0:.82 .2:.96 .95:.99 4:.99 4.85:.95 4.98:.88",
        belt = "1.5:1.26 4.8:1.33", win = "1.8-2.75 2.85-3.75 3.85-4.7", tumble = 0.8f,
    )
    // Utes: the cab only; the tub or tray runs from the end of [top] to the tail.
    private val hilux = Ps1Profile.of(
        top = "0:.8 .08:.98 .35:1.08 1.2:1.13 1.45c:1.17 2.05f:1.77 2.6:1.815 3.45r:1.79 3.52b:1.25",
        bot = "0:.45 .35:.42 3.52:.45",
        plan = "0:.78 .25:.9 .95:.9275 3.52:.92 5.325:.92",
        belt = "1.45:1.17 3.5:1.22", win = "2.05-2.8 2.9-3.42", tumble = 0.82f,
    )
    private val ranger = Ps1Profile.of(
        top = "0:.85 .08:1.02 .3:1.12 1.25:1.18 1.5c:1.21 2.1f:1.84 2.7:1.884 3.55r:1.86 3.62b:1.3",
        bot = "0:.45 .35:.42 3.62:.46",
        plan = "0:.82 .25:.94 .95:.959 3.62:.955 5.37:.955",
        belt = "1.5:1.21 3.6:1.26", win = "2.1-2.9 3-3.52", tumble = 0.82f,
    )
    private val lc79 = Ps1Profile.of(
        top = "0:.85 .05:1 .2:1.12 1.25:1.22 1.55c:1.27 1.95f:1.9 2.2:1.955 2.4r:1.93 2.45b:1.3",
        bot = "0:.47 .3:.45 2.45:.48",
        plan = "0:.88 .3:.92 2.45:.935 5.22:.935",
        belt = "1.55:1.27 2.45:1.3", win = "1.95-2.38", tumble = 0.86f,
    )

    private val HAND: Map<String, Ps1Profile> = mapOf(
        "ford-focus-rs-mk3" to focusRs, "ford-focus-rs-mk2" to focusRs, "ford-focus-st-mk4" to focusRs, "ford-focus-mk4" to focusRs,
        "hyundai-i30-pd" to i30, "hyundai-i30n-pd" to i30,
        "vw-golf-gti-mk8" to golf, "vw-golf-gti-mk7" to golf, "vw-golf-r-mk8" to golf, "vw-golf-mk8" to golf,
        "toyota-gr-yaris" to grYaris,
        "bmw-m3-g80" to bmwG80, "bmw-3-g20" to bmwG80, "bmw-5-g30" to bmwG80,
        "bmw-m3-e46" to bmwE46,
        "subaru-wrx-vb" to wrx, "subaru-wrx-va" to wrx, "subaru-wrx-sti-va" to wrx,
        "ford-mustang-s550" to mustang, "ford-mustang-s650" to mustang,
        "holden-commodore-vf" to commodore, "ford-falcon-fg" to commodore,
        "nissan-patrol-y62" to patrolY62,
        "toyota-lc300" to lc300, "toyota-lc200" to lc300,
        "toyota-hilux-an120" to hilux,
        "ford-ranger-p703" to ranger, "ford-ranger-raptor-p703" to ranger, "vw-amarok-2" to ranger,
        "toyota-lc79" to lc79,
    )

    private val cache = HashMap<String, Ps1Profile>()

    /** The body curves of [sh]: its hand-made profile stretched to its dimensions, else one built from its template. */
    fun of(sh: Ps1Shape): Ps1Profile = synchronized(cache) {
        cache.getOrPut(sh.id) { HAND[sh.id]?.scaled(sh.len, sh.wid, sh.hgt) ?: Ps1Profile.from(sh) }
    }

    fun isHandMade(id: String) = id in HAND
}
