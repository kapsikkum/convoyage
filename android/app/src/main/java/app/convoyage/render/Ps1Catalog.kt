package app.convoyage.render

import app.convoyage.render.Ps1Body.*
import app.convoyage.render.Ps1Face.*
import app.convoyage.render.Ps1Kit.*

/**
 * One real model (one generation): what it is called, which years, and its shape. Dimensions are the maker's published
 * length, width (without mirrors), height and wheelbase of a common trim, and its stock tyre. Motorcycles carry only
 * their body.
 */
class Ps1Model(
    val id: String,
    val make: String,
    /** Display name, e.g. "Focus RS". Its first word is the family the garage groups variants by. */
    val model: String,
    /** Generation or variant, e.g. "Mk3". */
    val gen: String,
    val years: IntRange,
    val body: Ps1Body,
    /** Ways people type the model; matched as whole words, spacing ignored ("i30n" = "i30 n"). */
    val aliases: List<String>,
    val shape: Ps1Shape?,
) {
    val family: String get() = Ps1Catalog.norm(model).substringBefore(' ')
    val yearsLabel: String get() = if (years.last >= 2100) "${years.first}–" else "${years.first}–${years.last}"
}

object Ps1Catalog {
    private const val NOW = 2100

    private val makes = mapOf(
        "vw" to "volkswagen", "merc" to "mercedes", "mercedesbenz" to "mercedes", "benz" to "mercedes", "mb" to "mercedes",
        "landrover" to "land rover", "lr" to "land rover", "gwm" to "great wall", "greatwall" to "great wall", "haval" to "great wall",
        "chev" to "chevrolet", "chevy" to "chevrolet", "dodge" to "ram", "hsv" to "holden", "fpv" to "ford", "harley" to "harley davidson",
        "harleydavidson" to "harley davidson", "hd" to "harley davidson", "mitsi" to "mitsubishi", "subie" to "subaru",
    )

    private fun c(
        id: String, make: String, model: String, gen: String, years: IntRange, body: Ps1Body,
        mm: IntArray, tyre: String, aliases: List<String>, face: Ps1Face = Generic, kit: Set<Ps1Kit> = emptySet(),
        t: Ps1Tpl.() -> Ps1Tpl = { this },
    ) = Ps1Model(id, make, model, gen, years, body, aliases, Ps1Shape.of(id, body, mm, tyre, Ps1Shapes.tpl(body).t(), face, kit))

    private fun bike(id: String, make: String, model: String, body: Ps1Body, vararg aliases: String) =
        Ps1Model(id, make, model, "", 1970..NOW, body, aliases.toList(), null)

    private fun mm(l: Int, w: Int, h: Int, wb: Int) = intArrayOf(l, w, h, wb)

    val ALL: List<Ps1Model> = listOf(
        // ---- BMW ----
        c("bmw-3-g20", "BMW", "3 Series", "G20", 2019..NOW, Sedan, mm(4709, 1827, 1442, 2851), "225/45R18", listOf("3 series", "320i", "330i", "330e", "m340i", "3er"), Kidney) { copy(roofR = 0.71f) },
        c("bmw-m3-g80", "BMW", "M3", "G80", 2021..NOW, Sedan, mm(4794, 1903, 1433, 2857), "275/35R19", listOf("m3", "m3 competition", "m3 cs"), KidneyTall, setOf(BootLip)) { copy(roofR = 0.71f) },
        c("bmw-m3-e46", "BMW", "M3", "E46", 2000..2006, Coupe, mm(4492, 1780, 1372, 2731), "255/40R18", listOf("m3", "e46 m3", "m3 e46"), Kidney) { copy(roofR = 0.67f, rw = 0.84f) },
        c("bmw-m3-e30", "BMW", "M3", "E30", 1986..1991, Coupe, mm(4345, 1680, 1370, 2562), "205/55R15", listOf("m3", "e30 m3", "m3 e30"), Kidney, setOf(Wing)) { copy(roofF = 0.44f, roofR = 0.70f, rw = 0.81f, nose = 0.54f, belt = 0.66f) },
        c("bmw-m4-g82", "BMW", "M4", "G82", 2021..NOW, Coupe, mm(4794, 1887, 1393, 2857), "275/35R19", listOf("m4", "m4 competition", "m4 csl"), KidneyTall, setOf(BootLip)),
        c("bmw-m2-g87", "BMW", "M2", "G87", 2023..NOW, Coupe, mm(4580, 1887, 1403, 2747), "275/35R19", listOf("m2"), Kidney, setOf(BootLip)),
        c("bmw-m2-f87", "BMW", "M2", "F87", 2016..2022, Coupe, mm(4468, 1854, 1410, 2693), "245/35R19", listOf("m2", "m2 competition"), Kidney, setOf(BootLip)),
        c("bmw-1-f40", "BMW", "1 Series", "F40", 2019..NOW, Hatch, mm(4319, 1799, 1434, 2670), "225/40R18", listOf("1 series", "118i", "m135i", "128ti"), Kidney, setOf(RoofSpoiler)),
        c("bmw-5-g30", "BMW", "5 Series", "G30", 2017..2023, Sedan, mm(4936, 1868, 1479, 2975), "245/40R19", listOf("5 series", "520i", "530i", "540i", "m5"), Kidney) { copy(roofR = 0.72f) },
        c("bmw-x3-g01", "BMW", "X3", "G01", 2018..2024, Suv, mm(4708, 1891, 1676, 2864), "245/50R19", listOf("x3", "x3 m40i"), Kidney, setOf(RoofRails)),
        c("bmw-x5-g05", "BMW", "X5", "G05", 2019..NOW, Suv, mm(4922, 2004, 1745, 2975), "275/45R20", listOf("x5", "x5 m"), KidneyTall, setOf(RoofRails)),

        // ---- Hyundai ----
        c("hyundai-i30-pd", "Hyundai", "i30", "PD hatch", 2017..NOW, Hatch, mm(4340, 1795, 1455, 2650), "225/45R17", listOf("i30", "i30 hatch"), Wide),
        c("hyundai-i30n-pd", "Hyundai", "i30 N", "PD hatch", 2018..NOW, HotHatch, mm(4335, 1795, 1447, 2650), "235/35R19", listOf("i30 n", "i30n", "i30 n hatch"), Wide, setOf(RoofSpoiler)),
        c("hyundai-i30-cn7", "Hyundai", "i30 Sedan", "CN7", 2020..NOW, Sedan, mm(4675, 1825, 1415, 2720), "225/45R17", listOf("i30 sedan", "elantra"), Wide) { copy(roofR = 0.70f, rw = 0.87f) },
        c("hyundai-i30n-cn7", "Hyundai", "i30 Sedan N", "CN7", 2022..NOW, Sedan, mm(4715, 1825, 1405, 2720), "245/35R19", listOf("i30 sedan n", "i30 n sedan", "elantra n"), Wide, setOf(BootLip)) { copy(roofR = 0.70f, rw = 0.87f) },
        c("hyundai-tucson-nx4", "Hyundai", "Tucson", "NX4", 2021..NOW, Suv, mm(4630, 1865, 1665, 2755), "235/55R19", listOf("tucson"), LightBar, setOf(RoofRails)),
        c("hyundai-kona-sx2", "Hyundai", "Kona", "SX2", 2023..NOW, Suv, mm(4350, 1825, 1580, 2660), "235/45R19", listOf("kona"), LightBar),
        c("hyundai-ioniq5", "Hyundai", "Ioniq 5", "NE", 2021..NOW, Suv, mm(4635, 1890, 1647, 3000), "255/45R20", listOf("ioniq 5", "ioniq5"), Slim) { copy(sill = 0.15f, roofR = 0.95f) },
        c("hyundai-staria", "Hyundai", "Staria", "US4", 2021..NOW, PeopleMover, mm(5253, 1997, 1990, 3273), "235/55R18", listOf("staria"), LightBar) { copy(ws = 0.12f, roofF = 0.25f) },

        // ---- Ford ----
        c("ford-focus-rs-mk3", "Ford", "Focus RS", "Mk3", 2016..2018, HotHatch, mm(4390, 1823, 1472, 2647), "235/35R19", listOf("focus rs", "focus rs mk3", "rs mk3"), Wide, setOf(RoofSpoiler)),
        c("ford-focus-rs-mk2", "Ford", "Focus RS", "Mk2", 2009..2011, HotHatch, mm(4402, 1842, 1484, 2640), "235/35R19", listOf("focus rs", "focus rs mk2", "rs mk2"), Wide, setOf(RoofSpoiler, Flares, Scoop)),
        c("ford-focus-st-mk4", "Ford", "Focus ST", "Mk4", 2020..2024, HotHatch, mm(4388, 1825, 1453, 2700), "235/40R18", listOf("focus st", "focus st mk4"), Wide, setOf(RoofSpoiler)),
        c("ford-focus-mk4", "Ford", "Focus", "Mk4 hatch", 2019..2024, Hatch, mm(4378, 1825, 1454, 2700), "215/50R17", listOf("focus", "focus hatch"), Wide),
        c("ford-fiesta-st-mk7", "Ford", "Fiesta ST", "Mk7", 2013..2018, HotHatch, mm(3975, 1722, 1495, 2490), "205/40R17", listOf("fiesta st", "fiesta"), Wide, setOf(RoofSpoiler)) { copy(doors = 2) },
        c("ford-mustang-s550", "Ford", "Mustang", "S550", 2015..2023, Muscle, mm(4784, 1916, 1381, 2720), "255/40R19", listOf("mustang", "mustang gt", "mustang mach 1"), Wide, setOf(TriTail, BootLip)),
        c("ford-mustang-s650", "Ford", "Mustang", "S650", 2024..NOW, Muscle, mm(4811, 1916, 1397, 2720), "255/40R19", listOf("mustang", "mustang gt", "mustang dark horse"), Wide, setOf(TriTail, BootLip)),
        c("ford-ranger-p703", "Ford", "Ranger", "Next-Gen", 2022..NOW, FourwdUte, mm(5370, 1918, 1884, 3270), "255/65R18", listOf("ranger", "ranger xlt", "ranger wildtrak", "ranger sport"), CClamp, setOf(SportsBar, Steps)),
        c("ford-ranger-raptor-p703", "Ford", "Ranger Raptor", "Next-Gen", 2022..NOW, FourwdUte, mm(5381, 2028, 1922, 3270), "285/70R17", listOf("ranger raptor", "raptor"), CClamp, setOf(Flares, Steps)),
        c("ford-ranger-px3", "Ford", "Ranger", "PX3", 2018..2022, FourwdUte, mm(5446, 1860, 1815, 3220), "265/60R18", listOf("ranger", "ranger px", "ranger px3", "ranger xlt", "ranger wildtrak"), Wide, setOf(SportsBar, Steps)),
        c("ford-everest-ua2", "Ford", "Everest", "Next-Gen", 2022..NOW, FourwdWagon, mm(4940, 1923, 1842, 2900), "255/60R20", listOf("everest"), CClamp, setOf(RoofRails, Steps)) { copy(roofR = 0.95f) },
        c("ford-falcon-fg", "Ford", "Falcon", "FG/FG X", 2008..2016, Sedan, mm(4955, 1868, 1453, 2838), "245/40R18", listOf("falcon", "falcon xr6", "falcon xr8", "fg falcon", "fgx", "xr6", "xr8"), Wide, setOf(BootLip)) { copy(roofR = 0.72f) },
        c("ford-falcon-ute-fg", "Ford", "Falcon Ute", "FG/FG X", 2008..2016, SingleCab, mm(5096, 1868, 1497, 3104), "245/40R18", listOf("falcon ute", "fg ute", "xr6 ute", "xr8 ute"), Wide),

        // ---- Holden ----
        c("holden-commodore-vf", "Holden", "Commodore", "VE/VF", 2006..2017, Sedan, mm(4964, 1898, 1471, 2915), "245/40R19", listOf("commodore", "commodore ss", "ssv", "ss v", "vf", "ve", "calais"), Wide, setOf(BootLip)) { copy(roofR = 0.71f) },
        c("holden-ute-vf", "Holden", "Ute", "VE/VF", 2007..2017, SingleCab, mm(5040, 1899, 1494, 3009), "245/40R19", listOf("ute", "ss ute", "ssv ute", "commodore ute", "vf ute", "ve ute"), Wide),
        c("holden-colorado-rg", "Holden", "Colorado", "RG", 2012..2020, FourwdUte, mm(5361, 1872, 1800, 3096), "255/65R17", listOf("colorado"), Wide, setOf(SportsBar, Steps)),

        // ---- Toyota ----
        c("toyota-hilux-an120", "Toyota", "Hilux", "SR5 dual cab", 2015..NOW, FourwdUte, mm(5325, 1855, 1815, 3085), "265/60R18", listOf("hilux", "hilux sr5", "hilux rogue", "hilux sr", "hilux gr sport"), Wide, setOf(SportsBar, Steps)),
        c("toyota-hilux-cc", "Toyota", "Hilux", "SR cab chassis", 2015..NOW, CabChassis, mm(5330, 1855, 1800, 3085), "265/65R17", listOf("hilux", "hilux workmate", "hilux cab chassis", "hilux tray"), Wide),
        c("toyota-lc300", "Toyota", "LandCruiser 300", "300", 2021..NOW, FourwdWagon, mm(4980, 1980, 1950, 2850), "265/65R18", listOf("landcruiser 300", "land cruiser 300", "lc300", "300 series"), Wide, setOf(RoofRails, Steps)),
        c("toyota-lc200", "Toyota", "LandCruiser 200", "200", 2007..2021, FourwdWagon, mm(4990, 1980, 1945, 2850), "285/60R18", listOf("landcruiser 200", "land cruiser 200", "lc200", "200 series", "landcruiser"), Wide, setOf(RoofRails, Steps)),
        c("toyota-lc79", "Toyota", "LandCruiser 79", "79 single cab", 2007..NOW, FourwdCabChassis, mm(5220, 1870, 1955, 3180), "265/70R16", listOf("landcruiser 79", "land cruiser 79", "lc79", "79 series", "70 series", "landcruiser 70"), Generic, setOf(Snorkel)),
        c("toyota-lc76", "Toyota", "LandCruiser 76", "76 wagon", 2007..NOW, FourwdWagon, mm(4910, 1870, 1940, 2730), "265/70R16", listOf("landcruiser 76", "lc76", "76 series"), Generic, setOf(Snorkel)) { copy(ws = 0.30f, roofF = 0.40f, roofR = 0.985f, belt = 0.64f) },
        c("toyota-lc78", "Toyota", "LandCruiser 78", "Troop Carrier", 2007..NOW, FourwdWagon, mm(5220, 1870, 2115, 2980), "265/70R16", listOf("troopy", "troop carrier", "landcruiser 78", "lc78", "78 series"), Generic, setOf(Snorkel)) { copy(ws = 0.28f, roofF = 0.37f, roofR = 0.99f, belt = 0.60f, nose = 0.55f, sill = 0.22f) },
        c("toyota-prado-150", "Toyota", "Prado", "150", 2009..2024, FourwdWagon, mm(4995, 1885, 1890, 2790), "265/65R17", listOf("prado", "landcruiser prado", "prado 150"), Wide, setOf(RoofRails, Spare, Steps)),
        c("toyota-prado-250", "Toyota", "Prado", "250", 2024..NOW, FourwdWagon, mm(4925, 1980, 1870, 2850), "265/65R18", listOf("prado", "landcruiser prado", "prado 250"), Generic, setOf(RoofRails, Steps)) { copy(ws = 0.29f, roofR = 0.975f) },
        c("toyota-rav4-xa50", "Toyota", "RAV4", "XA50", 2019..2025, Suv, mm(4600, 1855, 1685, 2690), "225/60R18", listOf("rav4", "rav 4"), Wide, setOf(RoofRails)),
        c("toyota-corolla-e210h", "Toyota", "Corolla", "hatch", 2018..NOW, Hatch, mm(4375, 1790, 1435, 2640), "225/40R18", listOf("corolla", "corolla hatch"), Wide) { copy(roofR = 0.78f) },
        c("toyota-corolla-e210s", "Toyota", "Corolla", "sedan", 2019..NOW, Sedan, mm(4630, 1780, 1435, 2700), "225/45R17", listOf("corolla sedan", "corolla"), Wide),
        c("toyota-camry-xv70", "Toyota", "Camry", "XV70", 2017..2024, Sedan, mm(4885, 1840, 1445, 2825), "235/45R18", listOf("camry"), Wide),
        c("toyota-gr-yaris", "Toyota", "GR Yaris", "GXPA16", 2020..NOW, HotHatch, mm(3995, 1805, 1455, 2560), "225/40R18", listOf("gr yaris", "yaris gr"), Wide, setOf(RoofSpoiler)) { copy(doors = 2, roofR = 0.74f, rw = 0.96f) },
        c("toyota-gr-corolla", "Toyota", "GR Corolla", "GZEA14", 2022..NOW, HotHatch, mm(4410, 1850, 1480, 2640), "235/40R18", listOf("gr corolla", "corolla gr"), Wide, setOf(RoofSpoiler, Flares)) { copy(roofR = 0.78f) },
        c("toyota-gr86", "Toyota", "GR86", "ZN8", 2022..NOW, Coupe, mm(4265, 1775, 1310, 2575), "215/40R18", listOf("gr86", "gr 86"), Wide, setOf(BootLip)),
        c("toyota-86-zn6", "Toyota", "86", "ZN6", 2012..2021, Coupe, mm(4240, 1775, 1285, 2570), "215/45R17", listOf("86", "toyota 86", "gts"), Wide, setOf(BootLip)),
        c("toyota-supra-a90", "Toyota", "Supra", "A90", 2019..NOW, Coupe, mm(4379, 1854, 1292, 2470), "255/35R19", listOf("supra", "gr supra", "a90", "mk5 supra"), Wide, setOf(Ducktail)) { copy(roofF = 0.47f, roofR = 0.62f, rw = 0.90f) },
        c("toyota-supra-a80", "Toyota", "Supra", "A80", 1993..2002, Coupe, mm(4520, 1810, 1275, 2550), "235/45R17", listOf("supra", "a80", "mk4 supra", "jza80"), Slim, setOf(Wing)) { copy(roofR = 0.64f, rw = 0.89f) },
        c("toyota-hiace-h300", "Toyota", "HiAce", "H300 LWB", 2019..NOW, Van, mm(5265, 1950, 1990, 3210), "215/70R16", listOf("hiace", "hi ace"), Generic) { copy(ws = 0.17f, roofF = 0.27f, nose = 0.50f) },

        // ---- Nissan ----
        c("nissan-patrol-y62", "Nissan", "Patrol", "Y62", 2012..NOW, FourwdWagon, mm(5165, 1995, 1940, 3075), "275/60R20", listOf("patrol", "patrol y62", "y62", "patrol ti", "patrol warrior"), Wide, setOf(RoofRails, Steps)),
        c("nissan-patrol-gu", "Nissan", "Patrol", "GU wagon", 1997..2016, FourwdWagon, mm(5050, 1940, 1855, 2970), "265/70R16", listOf("patrol", "gu patrol", "patrol gu", "gu"), Generic, setOf(Flares, RoofRails)) { copy(ws = 0.29f, roofF = 0.40f, roofR = 0.975f) },
        c("nissan-navara-d23", "Nissan", "Navara", "D23", 2015..NOW, FourwdUte, mm(5255, 1850, 1840, 3150), "255/60R18", listOf("navara", "np300", "navara pro 4x", "warrior"), Wide, setOf(SportsBar, Steps)),
        c("nissan-gtr-r35", "Nissan", "GT-R", "R35", 2007..NOW, Coupe, mm(4710, 1895, 1370, 2780), "255/40R20", listOf("gt r", "gtr", "r35", "r35 gtr", "gt r nismo"), Wide, setOf(Wing)) { copy(roofR = 0.66f, rw = 0.86f) },
        c("nissan-skyline-r33", "Nissan", "Skyline GT-R", "R33", 1995..1998, Coupe, mm(4675, 1780, 1360, 2720), "245/45R17", listOf("skyline", "r33", "r33 gtr", "r33 gt r", "skyline r33", "bcnr33", "skyline gt r", "skyline gtr"), Generic, setOf(Wing)),
        c("nissan-skyline-r32", "Nissan", "Skyline GT-R", "R32", 1989..1994, Coupe, mm(4545, 1755, 1340, 2615), "225/50R16", listOf("skyline", "r32", "r32 gtr", "r32 gt r", "skyline r32", "bnr32", "skyline gt r", "skyline gtr"), Generic, setOf(BootLip)) { copy(roofR = 0.66f, rw = 0.84f) },
        c("nissan-skyline-r34", "Nissan", "Skyline GT-R", "R34", 1999..2002, Coupe, mm(4600, 1785, 1360, 2665), "245/40R18", listOf("skyline", "r34", "r34 gtr", "skyline gt r", "skyline gtr"), Generic, setOf(Wing)) { copy(roofR = 0.66f, rw = 0.84f) },
        c("nissan-370z", "Nissan", "370Z", "Z34", 2009..2020, Coupe, mm(4250, 1845, 1315, 2550), "245/40R19", listOf("370z", "350z", "z"), Wide) { copy(roofR = 0.62f, rw = 0.92f) },

        // ---- Mazda ----
        c("mazda-mx5-nd", "Mazda", "MX-5", "ND", 2015..NOW, Roadster, mm(3915, 1735, 1225, 2310), "205/45R17", listOf("mx 5", "mx5", "miata", "mx 5 rf"), Slim),
        c("mazda-3-bp", "Mazda", "Mazda3", "BP hatch", 2019..NOW, Hatch, mm(4460, 1795, 1435, 2725), "215/45R18", listOf("mazda3", "3", "mazda 3", "mazda 3 hatch"), Wide) { copy(roofR = 0.76f) },
        c("mazda-cx5-kf", "Mazda", "CX-5", "KF", 2017..NOW, Suv, mm(4575, 1845, 1680, 2700), "225/55R19", listOf("cx 5", "cx5"), Wide, setOf(RoofRails)),
        c("mazda-bt50-tf", "Mazda", "BT-50", "TF", 2020..NOW, FourwdUte, mm(5280, 1870, 1810, 3125), "255/60R18", listOf("bt 50", "bt50"), Generic, setOf(SportsBar, Steps)),
        c("mazda-rx7-fd", "Mazda", "RX-7", "FD", 1992..2002, Coupe, mm(4295, 1760, 1230, 2425), "225/50R16", listOf("rx 7", "rx7", "fd", "fd3s"), Slim, setOf(Wing)) { copy(roofF = 0.48f, roofR = 0.62f, rw = 0.92f, nose = 0.46f) },

        // ---- Subaru ----
        c("subaru-wrx-vb", "Subaru", "WRX", "VB", 2022..NOW, Sedan, mm(4670, 1825, 1465, 2675), "245/40R18", listOf("wrx", "wrx sedan", "wrx rs", "wrx ts"), Wide, setOf(Scoop, Flares, BootLip)),
        c("subaru-wrx-sti-va", "Subaru", "WRX STI", "VA", 2014..2021, Sedan, mm(4595, 1795, 1475, 2650), "245/35R19", listOf("wrx sti", "sti", "wrx sti va"), Wide, setOf(Scoop, Wing)),
        c("subaru-wrx-va", "Subaru", "WRX", "VA", 2014..2021, Sedan, mm(4595, 1795, 1475, 2650), "235/45R17", listOf("wrx", "wrx va", "wrx premium"), Wide, setOf(Scoop, BootLip)),
        c("subaru-brz-zd8", "Subaru", "BRZ", "ZD8", 2021..NOW, Coupe, mm(4265, 1775, 1310, 2575), "215/40R18", listOf("brz"), Wide, setOf(BootLip)),
        c("subaru-forester-sk", "Subaru", "Forester", "SK", 2018..2025, Suv, mm(4625, 1815, 1730, 2670), "225/55R18", listOf("forester"), Wide, setOf(RoofRails)) { copy(roofR = 0.95f) },
        c("subaru-outback-bt", "Subaru", "Outback", "BT", 2021..NOW, Wagon, mm(4870, 1875, 1675, 2745), "225/60R18", listOf("outback"), Wide, setOf(RoofRails, Flares)) { copy(sill = 0.17f, nose = 0.55f) },

        // ---- Volkswagen ----
        c("vw-golf-gti-mk8", "Volkswagen", "Golf GTI", "Mk8", 2021..NOW, HotHatch, mm(4289, 1789, 1478, 2631), "225/40R18", listOf("golf gti", "gti", "golf gti mk8", "gti mk8"), LightBar, setOf(RoofSpoiler)) { copy(roofR = 0.82f) },
        c("vw-golf-gti-mk7", "Volkswagen", "Golf GTI", "Mk7", 2013..2020, HotHatch, mm(4268, 1790, 1442, 2631), "225/40R18", listOf("golf gti", "gti", "golf gti mk7", "gti mk7", "mk7"), Slim, setOf(RoofSpoiler)) { copy(roofR = 0.82f) },
        c("vw-golf-r-mk8", "Volkswagen", "Golf R", "Mk8", 2021..NOW, HotHatch, mm(4290, 1789, 1458, 2631), "235/35R19", listOf("golf r", "golf r mk8"), LightBar, setOf(RoofSpoiler)) { copy(roofR = 0.82f) },
        c("vw-golf-mk8", "Volkswagen", "Golf", "Mk8", 2020..NOW, Hatch, mm(4284, 1789, 1456, 2636), "225/45R17", listOf("golf"), LightBar) { copy(roofR = 0.82f) },
        c("vw-polo-gti-aw", "Volkswagen", "Polo GTI", "AW", 2018..NOW, HotHatch, mm(4074, 1751, 1438, 2549), "215/40R18", listOf("polo gti", "polo"), LightBar, setOf(RoofSpoiler)),
        c("vw-amarok-2", "Volkswagen", "Amarok", "NF", 2023..NOW, FourwdUte, mm(5350, 1910, 1884, 3270), "255/60R20", listOf("amarok"), Generic, setOf(SportsBar, Steps)),

        // ---- Isuzu, Mitsubishi, GWM, BYD, RAM ----
        c("isuzu-dmax-rg", "Isuzu", "D-Max", "RG", 2020..NOW, FourwdUte, mm(5265, 1870, 1790, 3125), "255/60R18", listOf("d max", "dmax"), Wide, setOf(SportsBar, Steps)),
        c("isuzu-mux-rj", "Isuzu", "MU-X", "RJ", 2021..NOW, FourwdWagon, mm(4850, 1870, 1825, 2855), "255/60R18", listOf("mu x", "mux"), Wide, setOf(RoofRails, Steps)) { copy(roofR = 0.95f) },
        c("mitsubishi-triton-mv", "Mitsubishi", "Triton", "MV", 2024..NOW, FourwdUte, mm(5320, 1865, 1795, 3130), "265/60R18", listOf("triton"), Wide, setOf(SportsBar, Steps)),
        c("mitsubishi-pajero-sport", "Mitsubishi", "Pajero Sport", "QE/QF", 2015..NOW, FourwdWagon, mm(4785, 1815, 1835, 2800), "265/60R18", listOf("pajero sport"), Wide, setOf(RoofRails, Steps)) { copy(roofR = 0.94f) },
        c("mitsubishi-evo-x", "Mitsubishi", "Lancer Evolution", "X", 2008..2016, Sedan, mm(4505, 1810, 1480, 2650), "245/40R18", listOf("evo", "evo x", "evolution", "lancer evolution", "lancer evo"), Wide, setOf(Wing, Scoop)),
        c("gwm-cannon", "Great Wall", "Cannon", "P-Series", 2020..NOW, FourwdUte, mm(5410, 1934, 1886, 3230), "265/60R18", listOf("cannon", "ute cannon", "gwm ute"), Wide, setOf(SportsBar, Steps)),
        c("byd-shark6", "BYD", "Shark 6", "", 2024..NOW, FourwdUte, mm(5457, 1971, 1925, 3260), "265/60R18", listOf("shark", "shark 6"), Slim, setOf(SportsBar, Steps)),
        c("byd-atto3", "BYD", "Atto 3", "", 2022..NOW, Suv, mm(4455, 1875, 1615, 2720), "215/55R18", listOf("atto 3", "atto3"), Slim, setOf(RoofRails)),
        c("ram-1500-dt", "RAM", "1500", "DT crew cab", 2019..NOW, FourwdUte, mm(5916, 2085, 1970, 3672), "275/55R20", listOf("1500", "ram 1500", "ram"), Wide, setOf(Steps)) { copy(cab = 0.64f, roofR = 0.615f) },

        // ---- Kia, Tesla, MG, Suzuki ----
        c("kia-stinger", "Kia", "Stinger", "CK", 2017..2023, Liftback, mm(4830, 1870, 1400, 2905), "225/40R19", listOf("stinger", "stinger gt"), Generic, setOf(BootLip)),
        c("kia-carnival-ka4", "Kia", "Carnival", "KA4", 2020..NOW, PeopleMover, mm(5155, 1995, 1775, 3090), "235/55R19", listOf("carnival"), Wide, setOf(RoofRails)),
        c("kia-sportage-nq5", "Kia", "Sportage", "NQ5", 2022..NOW, Suv, mm(4660, 1865, 1665, 2755), "235/55R19", listOf("sportage"), Generic, setOf(RoofRails)),
        c("kia-cerato-bd", "Kia", "Cerato", "BD hatch", 2018..2024, Hatch, mm(4510, 1800, 1440, 2700), "225/40R18", listOf("cerato", "cerato gt"), Generic) { copy(roofR = 0.76f) },
        c("tesla-model3", "Tesla", "Model 3", "", 2017..NOW, Sedan, mm(4720, 1849, 1441, 2875), "235/45R18", listOf("model 3", "model3", "m3"), Slim) { copy(roofF = 0.42f, roofR = 0.64f, rw = 0.87f) },
        c("tesla-modely", "Tesla", "Model Y", "", 2020..NOW, Suv, mm(4751, 1921, 1624, 2890), "255/45R19", listOf("model y", "modely"), Slim) { copy(sill = 0.17f, roofR = 0.80f, rw = 0.965f) },
        c("mg-zs", "MG", "ZS", "", 2017..NOW, Suv, mm(4323, 1809, 1653, 2585), "215/55R17", listOf("zs", "zs ev"), Wide, setOf(RoofRails)),
        c("mg-mg4", "MG", "MG4", "", 2022..NOW, Hatch, mm(4287, 1836, 1504, 2705), "215/55R17", listOf("mg4", "4"), Slim, setOf(RoofSpoiler)),
        c("suzuki-jimny-jb74", "Suzuki", "Jimny", "JB74 3-door", 2018..NOW, FourwdWagon, mm(3480, 1645, 1725, 2250), "195/80R15", listOf("jimny"), Round, setOf(Spare, Flares)) { copy(frontShare = 0.5f, ws = 0.27f, roofF = 0.37f, roofR = 0.97f, belt = 0.62f, nose = 0.58f) },
        c("suzuki-swift-sport-zc33", "Suzuki", "Swift Sport", "ZC33S", 2018..2024, HotHatch, mm(3890, 1735, 1495, 2450), "195/45R17", listOf("swift sport", "swift"), Wide, setOf(RoofSpoiler)) { copy(roofR = 0.80f) },

        // ---- Jeep, Land Rover, Mercedes, Audi, Porsche, Honda ----
        c("jeep-wrangler-jl4", "Jeep", "Wrangler", "JL 4-door", 2018..NOW, FourwdWagon, mm(4882, 1894, 1838, 3008), "255/70R18", listOf("wrangler", "wrangler unlimited", "wrangler rubicon", "rubicon", "jl"), SevenSlot, setOf(Spare, Flares, Steps)) { copy(ws = 0.31f, roofF = 0.38f, roofR = 0.98f, belt = 0.62f) },
        c("jeep-wrangler-jl2", "Jeep", "Wrangler", "JL 2-door", 2018..NOW, FourwdWagon, mm(4334, 1894, 1841, 2459), "255/70R18", listOf("wrangler", "wrangler 2 door", "wrangler sport"), SevenSlot, setOf(Spare, Flares)) { copy(ws = 0.34f, roofF = 0.42f, roofR = 0.98f, belt = 0.62f, doors = 2, longRoof = false) },
        c("lr-defender-110", "Land Rover", "Defender", "110 (L663)", 2020..NOW, FourwdWagon, mm(4758, 1996, 1967, 3022), "255/60R20", listOf("defender", "defender 110"), Generic, setOf(Spare, RoofRails)) { copy(ws = 0.27f, roofF = 0.36f, roofR = 0.98f) },
        c("lr-defender-90", "Land Rover", "Defender", "90 (L663)", 2020..NOW, FourwdWagon, mm(4323, 1996, 1969, 2587), "255/60R20", listOf("defender 90", "defender"), Generic, setOf(Spare, RoofRails)) { copy(ws = 0.29f, roofF = 0.39f, roofR = 0.98f, doors = 2) },
        c("merc-g-w463a", "Mercedes", "G-Class", "W463", 2018..NOW, FourwdWagon, mm(4873, 1984, 1979, 2890), "275/50R20", listOf("g class", "g wagon", "g63", "g 63", "g500", "g400d"), Round, setOf(Spare, Flares, Steps)) { copy(ws = 0.31f, roofF = 0.40f, roofR = 0.985f, belt = 0.62f, nose = 0.56f) },
        c("merc-a-w177", "Mercedes", "A-Class", "W177", 2018..NOW, Hatch, mm(4419, 1796, 1440, 2729), "225/45R18", listOf("a class", "a180", "a200", "a250", "a35", "a45", "a45 s"), Wide, setOf(RoofSpoiler)) { copy(roofR = 0.78f) },
        c("merc-c-w206", "Mercedes", "C-Class", "W206", 2021..NOW, Sedan, mm(4751, 1820, 1438, 2865), "225/45R18", listOf("c class", "c200", "c300", "c43", "c63"), Wide) { copy(roofR = 0.71f) },
        c("audi-rs3-8y", "Audi", "RS3", "8Y sedan", 2021..NOW, Sedan, mm(4542, 1851, 1412, 2631), "265/30R19", listOf("rs3", "rs 3", "s3", "a3 sedan"), Singleframe, setOf(BootLip)),
        c("audi-rs4-b9", "Audi", "RS4 Avant", "B9", 2018..NOW, Wagon, mm(4781, 1866, 1404, 2826), "265/35R19", listOf("rs4", "rs 4", "rs4 avant", "a4 avant", "s4 avant"), Singleframe, setOf(RoofRails, RoofSpoiler, Flares)) { copy(roofR = 0.93f) },
        c("audi-q5-fy", "Audi", "Q5", "FY", 2017..2024, Suv, mm(4663, 1893, 1659, 2819), "235/55R19", listOf("q5", "sq5"), Singleframe, setOf(RoofRails)),
        c("porsche-911-992", "Porsche", "911", "992", 2019..NOW, Coupe, mm(4519, 1852, 1298, 2450), "245/35R20", listOf("911", "carrera", "911 carrera", "911 turbo", "992"), Round, setOf(RearBar)) { copy(frontShare = 0.48f, roofF = 0.45f, roofR = 0.60f, rw = 0.97f, nose = 0.46f) },
        c("porsche-911-gt3-992", "Porsche", "911 GT3", "992", 2021..NOW, Coupe, mm(4573, 1852, 1279, 2457), "255/35R20", listOf("gt3", "911 gt3", "gt3 rs"), Round, setOf(RearBar, Wing)) { copy(frontShare = 0.48f, roofF = 0.45f, roofR = 0.60f, rw = 0.97f, nose = 0.46f) },
        c("honda-civic-typer-fl5", "Honda", "Civic Type R", "FL5", 2022..NOW, Liftback, mm(4595, 1890, 1405, 2735), "265/30R19", listOf("civic type r", "type r", "fl5"), Wide, setOf(Wing)),
        c("honda-civic-typer-fk8", "Honda", "Civic Type R", "FK8", 2017..2021, Liftback, mm(4557, 1877, 1434, 2700), "245/30R20", listOf("civic type r", "type r", "fk8"), Wide, setOf(Wing, Scoop, Flares)),
        c("honda-civic-fe", "Honda", "Civic", "FE hatch", 2022..NOW, Liftback, mm(4560, 1802, 1415, 2735), "235/40R18", listOf("civic"), Generic),

        // ---- Motorcycles: body only ----
        bike("yamaha-mt", "Yamaha", "MT", Naked, "mt 07", "mt07", "mt 09", "mt09", "mt 10", "mt 03", "xsr"),
        bike("yamaha-r", "Yamaha", "YZF-R", SportBike, "r1", "r6", "r7", "r3", "yzf r1", "yzf r6", "yzf r7"),
        bike("yamaha-tenere", "Yamaha", "Tenere 700", Adventure, "tenere", "tenere 700", "t7"),
        bike("yamaha-wr", "Yamaha", "WR / YZ", Dirt, "wr250r", "wr450f", "yz250f", "yz450f", "wr", "yz"),
        bike("kawasaki-ninja", "Kawasaki", "Ninja", SportBike, "ninja", "zx 6r", "zx6r", "zx 10r", "zx10r", "ninja 400", "ninja 650", "h2"),
        bike("kawasaki-z", "Kawasaki", "Z", Naked, "z900", "z650", "z400", "z h2", "z900rs"),
        bike("kawasaki-versys", "Kawasaki", "Versys", Adventure, "versys", "klr", "klr650"),
        bike("kawasaki-kx", "Kawasaki", "KX / KLX", Dirt, "kx", "klx", "kx450", "klx300"),
        bike("honda-cbr", "Honda", "CBR", SportBike, "cbr", "cbr600rr", "cbr1000rr", "fireblade", "cbr650r", "cbr500r"),
        bike("honda-cb", "Honda", "CB", Naked, "cb650r", "cb1000r", "cb500f", "cb300r", "hornet"),
        bike("honda-africa-twin", "Honda", "Africa Twin", Adventure, "africa twin", "crf1100l", "transalp", "nc750x"),
        bike("honda-goldwing", "Honda", "Gold Wing", Tourer, "gold wing", "goldwing"),
        bike("honda-crf", "Honda", "CRF", Dirt, "crf", "crf250l", "crf450", "crf300l", "xr650l", "xr"),
        bike("honda-scooter", "Honda", "Scooter", Scooter, "pcx", "forza", "adv", "adv350", "sh"),
        bike("honda-rebel", "Honda", "Rebel", Cruiser, "rebel", "shadow"),
        bike("suzuki-gsxr", "Suzuki", "GSX-R", SportBike, "gsx r", "gsxr", "gsx r1000", "gsx r750", "gsx r600", "hayabusa"),
        bike("suzuki-vstrom", "Suzuki", "V-Strom", Adventure, "v strom", "vstrom", "dr650", "drz400"),
        bike("suzuki-sv", "Suzuki", "SV / GSX-S", Naked, "sv650", "gsx s", "gsx s750", "gsx s1000"),
        bike("bmw-gs", "BMW", "GS", Adventure, "gs", "r1250gs", "r 1250 gs", "r1300gs", "f850gs", "f900gs", "g310gs"),
        bike("bmw-rr", "BMW", "S 1000 RR", SportBike, "s1000rr", "s 1000 rr", "m1000rr"),
        bike("bmw-rt", "BMW", "RT / K", Tourer, "r1250rt", "rt", "k1600", "k1600gt"),
        bike("bmw-r", "BMW", "R nineT / S 1000 R", Naked, "r nine t", "r ninet", "s1000r", "f900r"),
        bike("ktm-duke", "KTM", "Duke", Naked, "duke", "390 duke", "790 duke", "890 duke", "1290 super duke", "super duke"),
        bike("ktm-adv", "KTM", "Adventure", Adventure, "390 adventure", "890 adventure", "1290 super adventure", "adventure"),
        bike("ktm-rc", "KTM", "RC", SportBike, "rc 390", "rc390", "rc8"),
        bike("ktm-exc", "KTM", "EXC / SX", Dirt, "exc", "sx", "300 exc", "500 exc", "450 sx", "sx f", "exc f"),
        bike("ducati-panigale", "Ducati", "Panigale", SportBike, "panigale", "v4", "v2", "panigale v4", "959", "1299", "supersport"),
        bike("ducati-monster", "Ducati", "Monster", Naked, "monster", "streetfighter", "scrambler"),
        bike("ducati-multistrada", "Ducati", "Multistrada", Adventure, "multistrada", "desertx", "desert x"),
        bike("ducati-diavel", "Ducati", "Diavel", Cruiser, "diavel", "xdiavel"),
        bike("triumph-street", "Triumph", "Street / Speed", Naked, "street triple", "speed triple", "trident", "bonneville", "speed twin", "thruxton", "speed 400"),
        bike("triumph-tiger", "Triumph", "Tiger", Adventure, "tiger", "tiger 900", "tiger 1200", "scrambler 1200"),
        bike("triumph-rocket", "Triumph", "Rocket 3", Cruiser, "rocket 3", "rocket"),
        bike("harley", "Harley-Davidson", "Harley", Cruiser, "sportster", "softail", "fat boy", "fatboy", "street bob", "low rider", "breakout", "nightster", "iron 883", "forty eight"),
        bike("harley-touring", "Harley-Davidson", "Touring", Tourer, "road glide", "street glide", "road king", "electra glide", "ultra limited"),
        bike("harley-panamerica", "Harley-Davidson", "Pan America", Adventure, "pan america"),
        bike("indian", "Indian", "Indian", Cruiser, "scout", "chief", "chieftain", "springfield", "super chief"),
        bike("vespa", "Vespa", "Vespa", Scooter, "vespa", "gts", "primavera", "sprint"),
        bike("royal-enfield", "Royal Enfield", "Royal Enfield", Naked, "classic 350", "interceptor", "continental gt", "meteor", "bullet"),
        bike("royal-enfield-himalayan", "Royal Enfield", "Himalayan", Adventure, "himalayan"),
    )

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String?): Ps1Model? = id?.let { byId[it] }

    /** Lower case, words separated by single spaces, punctuation gone. */
    fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun compact(s: String) = norm(s).replace(" ", "")

    private fun makeKey(s: String): String {
        val c = compact(s)
        return compact(makes[c] ?: s)
    }

    /** Whether [alias] appears in [text] as a run of whole words, spacing ignored. */
    private fun wordsMatch(text: List<String>, alias: String): Boolean {
        val want = compact(alias)
        if (want.isEmpty()) return false
        for (i in text.indices) {
            val sb = StringBuilder()
            for (j in i until text.size) {
                sb.append(text[j])
                if (sb.length == want.length && sb.toString() == want) return true
                if (sb.length >= want.length) break
            }
        }
        return false
    }

    /** How well [m] fits: the longest alias that matched, plus a bonus when the year is in range. Null = no match. */
    private fun score(m: Ps1Model, make: String, model: String, year: Int?): Int? {
        val mk = makeKey(make)
        val modelWords = norm(model).split(' ').filter { it.isNotEmpty() }
        // Make and model typed together in the model box, e.g. "Ford Focus RS", also counts.
        val makeOk = mk.isNotEmpty() && mk == compact(m.make)
        val inModel = !makeOk && modelWords.isNotEmpty() && makeKey(modelWords[0]) == compact(m.make)
        if (!makeOk && !inModel) return null
        val words = if (inModel) modelWords.drop(1) else modelWords
        if (words.isEmpty()) return null
        val best = m.aliases.filter { wordsMatch(words, it) }.maxOfOrNull { compact(it).length } ?: return null
        val yearBonus = when {
            year == null -> if (m.years.last >= NOW) 1 else 0
            year in m.years -> 50
            else -> -50
        }
        return best * 4 + yearBonus
    }

    /** The best catalogue model for what was typed, if any. [kind] limits it to one kind. */
    fun best(make: String, model: String, year: Int?, kind: Ps1Kind? = null): Ps1Model? =
        ALL.asSequence()
            .filter { kind == null || it.body.kind == kind }
            .mapNotNull { m -> score(m, make, model, year)?.let { m to it } }
            .maxByOrNull { it.second }?.first

    /**
     * Every variant worth offering for what was typed: the matches and the rest of their family (i30, i30 N, i30
     * Sedan...), best first.
     */
    fun candidates(make: String, model: String, year: Int?): List<Ps1Model> {
        val scored = ALL.mapNotNull { m -> score(m, make, model, year)?.let { m to it } }.sortedByDescending { it.second }
        if (scored.isEmpty()) return emptyList()
        val families = scored.map { it.first.make to it.first.family }.toSet()
        val rest = ALL.filter { (it.make to it.family) in families && scored.none { s -> s.first === it } }
        return scored.map { it.first } + rest
    }
}
