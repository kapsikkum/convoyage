import Foundation

// Port of android/.../render/Ps1Catalog.kt: the catalogue of real models the garage can match a build against.

/// One real model (one generation): what it is called, which years, and its shape. Dimensions are the maker's published
/// length, width (without mirrors), height and wheelbase of a common trim, and its stock tyre. Motorcycles carry only
/// their body.
struct Ps1Model {
    let id: String
    let make: String
    /// Display name, e.g. "Focus RS". Its first word is the family the garage groups variants by.
    let model: String
    /// Generation or variant, e.g. "Mk3".
    let gen: String
    let years: ClosedRange<Int>
    let body: Ps1Body
    /// Ways people type the model; matched as whole words, spacing ignored ("i30n" = "i30 n").
    let aliases: [String]
    let shape: Ps1Shape?

    var family: String {
        let words: [String] = Ps1Catalog.norm(model).split(separator: " ").map(String.init)
        return words.first ?? ""
    }

    var yearsLabel: String {
        if years.upperBound >= 2100 { return "\(years.lowerBound)–" }
        return "\(years.lowerBound)–\(years.upperBound)"
    }
}

enum Ps1Catalog {
    static let now: Int = 2100

    private static let makes: [String: String] = [
        "vw": "volkswagen", "merc": "mercedes", "mercedesbenz": "mercedes", "benz": "mercedes", "mb": "mercedes",
        "landrover": "land rover", "lr": "land rover", "gwm": "great wall", "greatwall": "great wall", "haval": "great wall",
        "chev": "chevrolet", "chevy": "chevrolet", "dodge": "ram", "hsv": "holden", "fpv": "ford", "harley": "harley davidson",
        "harleydavidson": "harley davidson", "hd": "harley davidson", "mitsi": "mitsubishi", "subie": "subaru",
    ]

    private static func c(_ id: String, _ make: String, _ model: String, _ gen: String, _ years: ClosedRange<Int>,
                          _ body: Ps1Body, _ mm: [Int], _ tyre: String, _ aliases: [String],
                          _ face: Ps1Face = .generic, _ kit: Set<Ps1Kit> = [],
                          _ tweak: ((inout Ps1Tpl) -> Void)? = nil) -> Ps1Model {
        var tpl: Ps1Tpl = Ps1Shapes.tpl(body)
        tweak?(&tpl)
        return Ps1Model(id: id, make: make, model: model, gen: gen, years: years, body: body, aliases: aliases,
                        shape: Ps1Shape.of(id, body, mm, tyre, tpl, face, kit))
    }

    private static func bike(_ id: String, _ make: String, _ model: String, _ body: Ps1Body, _ aliases: String...) -> Ps1Model {
        return Ps1Model(id: id, make: make, model: model, gen: "", years: 1970...Ps1Catalog.now, body: body, aliases: aliases,
                        shape: nil)
    }

    private static func mm(_ l: Int, _ w: Int, _ h: Int, _ wb: Int) -> [Int] {
        return [l, w, h, wb]
    }

    static let all: [Ps1Model] = [
        // ---- BMW ----
        c("bmw-3-g20", "BMW", "3 Series", "G20", 2019...Ps1Catalog.now, .sedan, mm(4709, 1827, 1442, 2851), "225/45R18", ["3 series", "320i", "330i", "330e", "m340i", "3er"], .kidney) { (t: inout Ps1Tpl) in t.roofR = 0.71 },
        c("bmw-m3-g80", "BMW", "M3", "G80", 2021...Ps1Catalog.now, .sedan, mm(4794, 1903, 1433, 2857), "275/35R19", ["m3", "m3 competition", "m3 cs"], .kidneyTall, [.bootLip]) { (t: inout Ps1Tpl) in t.roofR = 0.71 },
        c("bmw-m3-e46", "BMW", "M3", "E46", 2000...2006, .coupe, mm(4492, 1780, 1372, 2731), "255/40R18", ["m3", "e46 m3", "m3 e46"], .kidney) { (t: inout Ps1Tpl) in t.roofR = 0.67; t.rw = 0.84 },
        c("bmw-m3-e30", "BMW", "M3", "E30", 1986...1991, .coupe, mm(4345, 1680, 1370, 2562), "205/55R15", ["m3", "e30 m3", "m3 e30"], .kidney, [.wing]) { (t: inout Ps1Tpl) in t.roofF = 0.44; t.roofR = 0.70; t.rw = 0.81; t.nose = 0.54; t.belt = 0.66 },
        c("bmw-m4-g82", "BMW", "M4", "G82", 2021...Ps1Catalog.now, .coupe, mm(4794, 1887, 1393, 2857), "275/35R19", ["m4", "m4 competition", "m4 csl"], .kidneyTall, [.bootLip]),
        c("bmw-m2-g87", "BMW", "M2", "G87", 2023...Ps1Catalog.now, .coupe, mm(4580, 1887, 1403, 2747), "275/35R19", ["m2"], .kidney, [.bootLip]),
        c("bmw-m2-f87", "BMW", "M2", "F87", 2016...2022, .coupe, mm(4468, 1854, 1410, 2693), "245/35R19", ["m2", "m2 competition"], .kidney, [.bootLip]),
        c("bmw-1-f40", "BMW", "1 Series", "F40", 2019...Ps1Catalog.now, .hatch, mm(4319, 1799, 1434, 2670), "225/40R18", ["1 series", "118i", "m135i", "128ti"], .kidney, [.roofSpoiler]),
        c("bmw-5-g30", "BMW", "5 Series", "G30", 2017...2023, .sedan, mm(4936, 1868, 1479, 2975), "245/40R19", ["5 series", "520i", "530i", "540i", "m5"], .kidney) { (t: inout Ps1Tpl) in t.roofR = 0.72 },
        c("bmw-x3-g01", "BMW", "X3", "G01", 2018...2024, .suv, mm(4708, 1891, 1676, 2864), "245/50R19", ["x3", "x3 m40i"], .kidney, [.roofRails]),
        c("bmw-x5-g05", "BMW", "X5", "G05", 2019...Ps1Catalog.now, .suv, mm(4922, 2004, 1745, 2975), "275/45R20", ["x5", "x5 m"], .kidneyTall, [.roofRails]),

        // ---- Hyundai ----
        c("hyundai-i30-pd", "Hyundai", "i30", "PD hatch", 2017...Ps1Catalog.now, .hatch, mm(4340, 1795, 1455, 2650), "225/45R17", ["i30", "i30 hatch"], .wide),
        c("hyundai-i30n-pd", "Hyundai", "i30 N", "PD hatch", 2018...Ps1Catalog.now, .hotHatch, mm(4335, 1795, 1447, 2650), "235/35R19", ["i30 n", "i30n", "i30 n hatch"], .wide, [.roofSpoiler]),
        c("hyundai-i30-cn7", "Hyundai", "i30 Sedan", "CN7", 2020...Ps1Catalog.now, .sedan, mm(4675, 1825, 1415, 2720), "225/45R17", ["i30 sedan", "elantra"], .wide) { (t: inout Ps1Tpl) in t.roofR = 0.70; t.rw = 0.87 },
        c("hyundai-i30n-cn7", "Hyundai", "i30 Sedan N", "CN7", 2022...Ps1Catalog.now, .sedan, mm(4715, 1825, 1405, 2720), "245/35R19", ["i30 sedan n", "i30 n sedan", "elantra n"], .wide, [.bootLip]) { (t: inout Ps1Tpl) in t.roofR = 0.70; t.rw = 0.87 },
        c("hyundai-tucson-nx4", "Hyundai", "Tucson", "NX4", 2021...Ps1Catalog.now, .suv, mm(4630, 1865, 1665, 2755), "235/55R19", ["tucson"], .lightBar, [.roofRails]),
        c("hyundai-kona-sx2", "Hyundai", "Kona", "SX2", 2023...Ps1Catalog.now, .suv, mm(4350, 1825, 1580, 2660), "235/45R19", ["kona"], .lightBar),
        c("hyundai-ioniq5", "Hyundai", "Ioniq 5", "NE", 2021...Ps1Catalog.now, .suv, mm(4635, 1890, 1647, 3000), "255/45R20", ["ioniq 5", "ioniq5"], .slim) { (t: inout Ps1Tpl) in t.sill = 0.15; t.roofR = 0.95 },
        c("hyundai-staria", "Hyundai", "Staria", "US4", 2021...Ps1Catalog.now, .peopleMover, mm(5253, 1997, 1990, 3273), "235/55R18", ["staria"], .lightBar) { (t: inout Ps1Tpl) in t.ws = 0.12; t.roofF = 0.25 },

        // ---- Ford ----
        c("ford-focus-rs-mk3", "Ford", "Focus RS", "Mk3", 2016...2018, .hotHatch, mm(4390, 1823, 1472, 2647), "235/35R19", ["focus rs", "focus rs mk3", "rs mk3"], .wide, [.roofSpoiler]),
        c("ford-focus-rs-mk2", "Ford", "Focus RS", "Mk2", 2009...2011, .hotHatch, mm(4402, 1842, 1484, 2640), "235/35R19", ["focus rs", "focus rs mk2", "rs mk2"], .wide, [.roofSpoiler, .flares, .scoop]),
        c("ford-focus-st-mk4", "Ford", "Focus ST", "Mk4", 2020...2024, .hotHatch, mm(4388, 1825, 1453, 2700), "235/40R18", ["focus st", "focus st mk4"], .wide, [.roofSpoiler]),
        c("ford-focus-mk4", "Ford", "Focus", "Mk4 hatch", 2019...2024, .hatch, mm(4378, 1825, 1454, 2700), "215/50R17", ["focus", "focus hatch"], .wide),
        c("ford-fiesta-st-mk7", "Ford", "Fiesta ST", "Mk7", 2013...2018, .hotHatch, mm(3975, 1722, 1495, 2490), "205/40R17", ["fiesta st", "fiesta"], .wide, [.roofSpoiler]) { (t: inout Ps1Tpl) in t.doors = 2 },
        c("ford-mustang-s550", "Ford", "Mustang", "S550", 2015...2023, .muscle, mm(4784, 1916, 1381, 2720), "255/40R19", ["mustang", "mustang gt", "mustang mach 1"], .wide, [.triTail, .bootLip]),
        c("ford-mustang-s650", "Ford", "Mustang", "S650", 2024...Ps1Catalog.now, .muscle, mm(4811, 1916, 1397, 2720), "255/40R19", ["mustang", "mustang gt", "mustang dark horse"], .wide, [.triTail, .bootLip]),
        c("ford-ranger-p703", "Ford", "Ranger", "Next-Gen", 2022...Ps1Catalog.now, .fourwdUte, mm(5370, 1918, 1884, 3270), "255/65R18", ["ranger", "ranger xlt", "ranger wildtrak", "ranger sport"], .cClamp, [.sportsBar, .steps]),
        c("ford-ranger-raptor-p703", "Ford", "Ranger Raptor", "Next-Gen", 2022...Ps1Catalog.now, .fourwdUte, mm(5381, 2028, 1922, 3270), "285/70R17", ["ranger raptor", "raptor"], .cClamp, [.flares, .steps]),
        c("ford-ranger-px3", "Ford", "Ranger", "PX3", 2018...2022, .fourwdUte, mm(5446, 1860, 1815, 3220), "265/60R18", ["ranger", "ranger px", "ranger px3", "ranger xlt", "ranger wildtrak"], .wide, [.sportsBar, .steps]),
        c("ford-everest-ua2", "Ford", "Everest", "Next-Gen", 2022...Ps1Catalog.now, .fourwdWagon, mm(4940, 1923, 1842, 2900), "255/60R20", ["everest"], .cClamp, [.roofRails, .steps]) { (t: inout Ps1Tpl) in t.roofR = 0.95 },
        c("ford-falcon-fg", "Ford", "Falcon", "FG/FG X", 2008...2016, .sedan, mm(4955, 1868, 1453, 2838), "245/40R18", ["falcon", "falcon xr6", "falcon xr8", "fg falcon", "fgx", "xr6", "xr8"], .wide, [.bootLip]) { (t: inout Ps1Tpl) in t.roofR = 0.72 },
        c("ford-falcon-ute-fg", "Ford", "Falcon Ute", "FG/FG X", 2008...2016, .singleCab, mm(5096, 1868, 1497, 3104), "245/40R18", ["falcon ute", "fg ute", "xr6 ute", "xr8 ute"], .wide),

        // ---- Holden ----
        c("holden-commodore-vf", "Holden", "Commodore", "VE/VF", 2006...2017, .sedan, mm(4964, 1898, 1471, 2915), "245/40R19", ["commodore", "commodore ss", "ssv", "ss v", "vf", "ve", "calais"], .wide, [.bootLip]) { (t: inout Ps1Tpl) in t.roofR = 0.71 },
        c("holden-ute-vf", "Holden", "Ute", "VE/VF", 2007...2017, .singleCab, mm(5040, 1899, 1494, 3009), "245/40R19", ["ute", "ss ute", "ssv ute", "commodore ute", "vf ute", "ve ute"], .wide),
        c("holden-colorado-rg", "Holden", "Colorado", "RG", 2012...2020, .fourwdUte, mm(5361, 1872, 1800, 3096), "255/65R17", ["colorado"], .wide, [.sportsBar, .steps]),

        // ---- Toyota ----
        c("toyota-hilux-an120", "Toyota", "Hilux", "SR5 dual cab", 2015...Ps1Catalog.now, .fourwdUte, mm(5325, 1855, 1815, 3085), "265/60R18", ["hilux", "hilux sr5", "hilux rogue", "hilux sr", "hilux gr sport"], .wide, [.sportsBar, .steps]),
        c("toyota-hilux-cc", "Toyota", "Hilux", "SR cab chassis", 2015...Ps1Catalog.now, .cabChassis, mm(5330, 1855, 1800, 3085), "265/65R17", ["hilux", "hilux workmate", "hilux cab chassis", "hilux tray"], .wide),
        c("toyota-lc300", "Toyota", "LandCruiser 300", "300", 2021...Ps1Catalog.now, .fourwdWagon, mm(4980, 1980, 1950, 2850), "265/65R18", ["landcruiser 300", "land cruiser 300", "lc300", "300 series"], .wide, [.roofRails, .steps]),
        c("toyota-lc200", "Toyota", "LandCruiser 200", "200", 2007...2021, .fourwdWagon, mm(4990, 1980, 1945, 2850), "285/60R18", ["landcruiser 200", "land cruiser 200", "lc200", "200 series", "landcruiser"], .wide, [.roofRails, .steps]),
        c("toyota-lc79", "Toyota", "LandCruiser 79", "79 single cab", 2007...Ps1Catalog.now, .fourwdCabChassis, mm(5220, 1870, 1955, 3180), "265/70R16", ["landcruiser 79", "land cruiser 79", "lc79", "79 series", "70 series", "landcruiser 70"], .generic, [.snorkel]),
        c("toyota-lc76", "Toyota", "LandCruiser 76", "76 wagon", 2007...Ps1Catalog.now, .fourwdWagon, mm(4910, 1870, 1940, 2730), "265/70R16", ["landcruiser 76", "lc76", "76 series"], .generic, [.snorkel]) { (t: inout Ps1Tpl) in t.ws = 0.30; t.roofF = 0.40; t.roofR = 0.985; t.belt = 0.64 },
        c("toyota-lc78", "Toyota", "LandCruiser 78", "Troop Carrier", 2007...Ps1Catalog.now, .fourwdWagon, mm(5220, 1870, 2115, 2980), "265/70R16", ["troopy", "troop carrier", "landcruiser 78", "lc78", "78 series"], .generic, [.snorkel]) { (t: inout Ps1Tpl) in t.ws = 0.28; t.roofF = 0.37; t.roofR = 0.99; t.belt = 0.60; t.nose = 0.55; t.sill = 0.22 },
        c("toyota-prado-150", "Toyota", "Prado", "150", 2009...2024, .fourwdWagon, mm(4995, 1885, 1890, 2790), "265/65R17", ["prado", "landcruiser prado", "prado 150"], .wide, [.roofRails, .spare, .steps]),
        c("toyota-prado-250", "Toyota", "Prado", "250", 2024...Ps1Catalog.now, .fourwdWagon, mm(4925, 1980, 1870, 2850), "265/65R18", ["prado", "landcruiser prado", "prado 250"], .generic, [.roofRails, .steps]) { (t: inout Ps1Tpl) in t.ws = 0.29; t.roofR = 0.975 },
        c("toyota-rav4-xa50", "Toyota", "RAV4", "XA50", 2019...2025, .suv, mm(4600, 1855, 1685, 2690), "225/60R18", ["rav4", "rav 4"], .wide, [.roofRails]),
        c("toyota-corolla-e210h", "Toyota", "Corolla", "hatch", 2018...Ps1Catalog.now, .hatch, mm(4375, 1790, 1435, 2640), "225/40R18", ["corolla", "corolla hatch"], .wide) { (t: inout Ps1Tpl) in t.roofR = 0.78 },
        c("toyota-corolla-e210s", "Toyota", "Corolla", "sedan", 2019...Ps1Catalog.now, .sedan, mm(4630, 1780, 1435, 2700), "225/45R17", ["corolla sedan", "corolla"], .wide),
        c("toyota-camry-xv70", "Toyota", "Camry", "XV70", 2017...2024, .sedan, mm(4885, 1840, 1445, 2825), "235/45R18", ["camry"], .wide),
        c("toyota-gr-yaris", "Toyota", "GR Yaris", "GXPA16", 2020...Ps1Catalog.now, .hotHatch, mm(3995, 1805, 1455, 2560), "225/40R18", ["gr yaris", "yaris gr"], .wide, [.roofSpoiler]) { (t: inout Ps1Tpl) in t.doors = 2; t.roofR = 0.74; t.rw = 0.96 },
        c("toyota-gr-corolla", "Toyota", "GR Corolla", "GZEA14", 2022...Ps1Catalog.now, .hotHatch, mm(4410, 1850, 1480, 2640), "235/40R18", ["gr corolla", "corolla gr"], .wide, [.roofSpoiler, .flares]) { (t: inout Ps1Tpl) in t.roofR = 0.78 },
        c("toyota-gr86", "Toyota", "GR86", "ZN8", 2022...Ps1Catalog.now, .coupe, mm(4265, 1775, 1310, 2575), "215/40R18", ["gr86", "gr 86"], .wide, [.bootLip]),
        c("toyota-86-zn6", "Toyota", "86", "ZN6", 2012...2021, .coupe, mm(4240, 1775, 1285, 2570), "215/45R17", ["86", "toyota 86", "gts"], .wide, [.bootLip]),
        c("toyota-supra-a90", "Toyota", "Supra", "A90", 2019...Ps1Catalog.now, .coupe, mm(4379, 1854, 1292, 2470), "255/35R19", ["supra", "gr supra", "a90", "mk5 supra"], .wide, [.ducktail]) { (t: inout Ps1Tpl) in t.roofF = 0.47; t.roofR = 0.62; t.rw = 0.90 },
        c("toyota-supra-a80", "Toyota", "Supra", "A80", 1993...2002, .coupe, mm(4520, 1810, 1275, 2550), "235/45R17", ["supra", "a80", "mk4 supra", "jza80"], .slim, [.wing]) { (t: inout Ps1Tpl) in t.roofR = 0.64; t.rw = 0.89 },
        c("toyota-hiace-h300", "Toyota", "HiAce", "H300 LWB", 2019...Ps1Catalog.now, .van, mm(5265, 1950, 1990, 3210), "215/70R16", ["hiace", "hi ace"], .generic) { (t: inout Ps1Tpl) in t.ws = 0.17; t.roofF = 0.27; t.nose = 0.50 },

        // ---- Nissan ----
        c("nissan-patrol-y62", "Nissan", "Patrol", "Y62", 2012...Ps1Catalog.now, .fourwdWagon, mm(5165, 1995, 1940, 3075), "275/60R20", ["patrol", "patrol y62", "y62", "patrol ti", "patrol warrior"], .wide, [.roofRails, .steps]),
        c("nissan-patrol-gu", "Nissan", "Patrol", "GU wagon", 1997...2016, .fourwdWagon, mm(5050, 1940, 1855, 2970), "265/70R16", ["patrol", "gu patrol", "patrol gu", "gu"], .generic, [.flares, .roofRails]) { (t: inout Ps1Tpl) in t.ws = 0.29; t.roofF = 0.40; t.roofR = 0.975 },
        c("nissan-navara-d23", "Nissan", "Navara", "D23", 2015...Ps1Catalog.now, .fourwdUte, mm(5255, 1850, 1840, 3150), "255/60R18", ["navara", "np300", "navara pro 4x", "warrior"], .wide, [.sportsBar, .steps]),
        c("nissan-gtr-r35", "Nissan", "GT-R", "R35", 2007...Ps1Catalog.now, .coupe, mm(4710, 1895, 1370, 2780), "255/40R20", ["gt r", "gtr", "r35", "r35 gtr", "gt r nismo"], .wide, [.wing]) { (t: inout Ps1Tpl) in t.roofR = 0.66; t.rw = 0.86 },
        c("nissan-skyline-r33", "Nissan", "Skyline GT-R", "R33", 1995...1998, .coupe, mm(4675, 1780, 1360, 2720), "245/45R17", ["skyline", "r33", "r33 gtr", "r33 gt r", "skyline r33", "bcnr33", "skyline gt r", "skyline gtr"], .generic, [.wing]),
        c("nissan-skyline-r32", "Nissan", "Skyline GT-R", "R32", 1989...1994, .coupe, mm(4545, 1755, 1340, 2615), "225/50R16", ["skyline", "r32", "r32 gtr", "r32 gt r", "skyline r32", "bnr32", "skyline gt r", "skyline gtr"], .generic, [.bootLip]) { (t: inout Ps1Tpl) in t.roofR = 0.66; t.rw = 0.84 },
        c("nissan-skyline-r34", "Nissan", "Skyline GT-R", "R34", 1999...2002, .coupe, mm(4600, 1785, 1360, 2665), "245/40R18", ["skyline", "r34", "r34 gtr", "skyline gt r", "skyline gtr"], .generic, [.wing]) { (t: inout Ps1Tpl) in t.roofR = 0.66; t.rw = 0.84 },
        c("nissan-370z", "Nissan", "370Z", "Z34", 2009...2020, .coupe, mm(4250, 1845, 1315, 2550), "245/40R19", ["370z", "350z", "z"], .wide) { (t: inout Ps1Tpl) in t.roofR = 0.62; t.rw = 0.92 },

        // ---- Mazda ----
        c("mazda-mx5-nd", "Mazda", "MX-5", "ND", 2015...Ps1Catalog.now, .roadster, mm(3915, 1735, 1225, 2310), "205/45R17", ["mx 5", "mx5", "miata", "mx 5 rf"], .slim),
        c("mazda-3-bp", "Mazda", "Mazda3", "BP hatch", 2019...Ps1Catalog.now, .hatch, mm(4460, 1795, 1435, 2725), "215/45R18", ["mazda3", "3", "mazda 3", "mazda 3 hatch"], .wide) { (t: inout Ps1Tpl) in t.roofR = 0.76 },
        c("mazda-cx5-kf", "Mazda", "CX-5", "KF", 2017...Ps1Catalog.now, .suv, mm(4575, 1845, 1680, 2700), "225/55R19", ["cx 5", "cx5"], .wide, [.roofRails]),
        c("mazda-bt50-tf", "Mazda", "BT-50", "TF", 2020...Ps1Catalog.now, .fourwdUte, mm(5280, 1870, 1810, 3125), "255/60R18", ["bt 50", "bt50"], .generic, [.sportsBar, .steps]),
        c("mazda-rx7-fd", "Mazda", "RX-7", "FD", 1992...2002, .coupe, mm(4295, 1760, 1230, 2425), "225/50R16", ["rx 7", "rx7", "fd", "fd3s"], .slim, [.wing]) { (t: inout Ps1Tpl) in t.roofF = 0.48; t.roofR = 0.62; t.rw = 0.92; t.nose = 0.46 },

        // ---- Subaru ----
        c("subaru-wrx-vb", "Subaru", "WRX", "VB", 2022...Ps1Catalog.now, .sedan, mm(4670, 1825, 1465, 2675), "245/40R18", ["wrx", "wrx sedan", "wrx rs", "wrx ts"], .wide, [.scoop, .flares, .bootLip]),
        c("subaru-wrx-sti-va", "Subaru", "WRX STI", "VA", 2014...2021, .sedan, mm(4595, 1795, 1475, 2650), "245/35R19", ["wrx sti", "sti", "wrx sti va"], .wide, [.scoop, .wing]),
        c("subaru-wrx-va", "Subaru", "WRX", "VA", 2014...2021, .sedan, mm(4595, 1795, 1475, 2650), "235/45R17", ["wrx", "wrx va", "wrx premium"], .wide, [.scoop, .bootLip]),
        c("subaru-brz-zd8", "Subaru", "BRZ", "ZD8", 2021...Ps1Catalog.now, .coupe, mm(4265, 1775, 1310, 2575), "215/40R18", ["brz"], .wide, [.bootLip]),
        c("subaru-forester-sk", "Subaru", "Forester", "SK", 2018...2025, .suv, mm(4625, 1815, 1730, 2670), "225/55R18", ["forester"], .wide, [.roofRails]) { (t: inout Ps1Tpl) in t.roofR = 0.95 },
        c("subaru-outback-bt", "Subaru", "Outback", "BT", 2021...Ps1Catalog.now, .wagon, mm(4870, 1875, 1675, 2745), "225/60R18", ["outback"], .wide, [.roofRails, .flares]) { (t: inout Ps1Tpl) in t.sill = 0.17; t.nose = 0.55 },

        // ---- Volkswagen ----
        c("vw-golf-gti-mk8", "Volkswagen", "Golf GTI", "Mk8", 2021...Ps1Catalog.now, .hotHatch, mm(4289, 1789, 1478, 2631), "225/40R18", ["golf gti", "gti", "golf gti mk8", "gti mk8"], .lightBar, [.roofSpoiler]) { (t: inout Ps1Tpl) in t.roofR = 0.82 },
        c("vw-golf-gti-mk7", "Volkswagen", "Golf GTI", "Mk7", 2013...2020, .hotHatch, mm(4268, 1790, 1442, 2631), "225/40R18", ["golf gti", "gti", "golf gti mk7", "gti mk7", "mk7"], .slim, [.roofSpoiler]) { (t: inout Ps1Tpl) in t.roofR = 0.82 },
        c("vw-golf-r-mk8", "Volkswagen", "Golf R", "Mk8", 2021...Ps1Catalog.now, .hotHatch, mm(4290, 1789, 1458, 2631), "235/35R19", ["golf r", "golf r mk8"], .lightBar, [.roofSpoiler]) { (t: inout Ps1Tpl) in t.roofR = 0.82 },
        c("vw-golf-mk8", "Volkswagen", "Golf", "Mk8", 2020...Ps1Catalog.now, .hatch, mm(4284, 1789, 1456, 2636), "225/45R17", ["golf"], .lightBar) { (t: inout Ps1Tpl) in t.roofR = 0.82 },
        c("vw-polo-gti-aw", "Volkswagen", "Polo GTI", "AW", 2018...Ps1Catalog.now, .hotHatch, mm(4074, 1751, 1438, 2549), "215/40R18", ["polo gti", "polo"], .lightBar, [.roofSpoiler]),
        c("vw-amarok-2", "Volkswagen", "Amarok", "NF", 2023...Ps1Catalog.now, .fourwdUte, mm(5350, 1910, 1884, 3270), "255/60R20", ["amarok"], .generic, [.sportsBar, .steps]),

        // ---- Isuzu, Mitsubishi, GWM, BYD, RAM ----
        c("isuzu-dmax-rg", "Isuzu", "D-Max", "RG", 2020...Ps1Catalog.now, .fourwdUte, mm(5265, 1870, 1790, 3125), "255/60R18", ["d max", "dmax"], .wide, [.sportsBar, .steps]),
        c("isuzu-mux-rj", "Isuzu", "MU-X", "RJ", 2021...Ps1Catalog.now, .fourwdWagon, mm(4850, 1870, 1825, 2855), "255/60R18", ["mu x", "mux"], .wide, [.roofRails, .steps]) { (t: inout Ps1Tpl) in t.roofR = 0.95 },
        c("mitsubishi-triton-mv", "Mitsubishi", "Triton", "MV", 2024...Ps1Catalog.now, .fourwdUte, mm(5320, 1865, 1795, 3130), "265/60R18", ["triton"], .wide, [.sportsBar, .steps]),
        c("mitsubishi-pajero-sport", "Mitsubishi", "Pajero Sport", "QE/QF", 2015...Ps1Catalog.now, .fourwdWagon, mm(4785, 1815, 1835, 2800), "265/60R18", ["pajero sport"], .wide, [.roofRails, .steps]) { (t: inout Ps1Tpl) in t.roofR = 0.94 },
        c("mitsubishi-evo-x", "Mitsubishi", "Lancer Evolution", "X", 2008...2016, .sedan, mm(4505, 1810, 1480, 2650), "245/40R18", ["evo", "evo x", "evolution", "lancer evolution", "lancer evo"], .wide, [.wing, .scoop]),
        c("gwm-cannon", "Great Wall", "Cannon", "P-Series", 2020...Ps1Catalog.now, .fourwdUte, mm(5410, 1934, 1886, 3230), "265/60R18", ["cannon", "ute cannon", "gwm ute"], .wide, [.sportsBar, .steps]),
        c("byd-shark6", "BYD", "Shark 6", "", 2024...Ps1Catalog.now, .fourwdUte, mm(5457, 1971, 1925, 3260), "265/60R18", ["shark", "shark 6"], .slim, [.sportsBar, .steps]),
        c("byd-atto3", "BYD", "Atto 3", "", 2022...Ps1Catalog.now, .suv, mm(4455, 1875, 1615, 2720), "215/55R18", ["atto 3", "atto3"], .slim, [.roofRails]),
        c("ram-1500-dt", "RAM", "1500", "DT crew cab", 2019...Ps1Catalog.now, .fourwdUte, mm(5916, 2085, 1970, 3672), "275/55R20", ["1500", "ram 1500", "ram"], .wide, [.steps]) { (t: inout Ps1Tpl) in t.cab = 0.64; t.roofR = 0.615 },

        // ---- Kia, Tesla, MG, Suzuki ----
        c("kia-stinger", "Kia", "Stinger", "CK", 2017...2023, .liftback, mm(4830, 1870, 1400, 2905), "225/40R19", ["stinger", "stinger gt"], .generic, [.bootLip]),
        c("kia-carnival-ka4", "Kia", "Carnival", "KA4", 2020...Ps1Catalog.now, .peopleMover, mm(5155, 1995, 1775, 3090), "235/55R19", ["carnival"], .wide, [.roofRails]),
        c("kia-sportage-nq5", "Kia", "Sportage", "NQ5", 2022...Ps1Catalog.now, .suv, mm(4660, 1865, 1665, 2755), "235/55R19", ["sportage"], .generic, [.roofRails]),
        c("kia-cerato-bd", "Kia", "Cerato", "BD hatch", 2018...2024, .hatch, mm(4510, 1800, 1440, 2700), "225/40R18", ["cerato", "cerato gt"], .generic) { (t: inout Ps1Tpl) in t.roofR = 0.76 },
        c("tesla-model3", "Tesla", "Model 3", "", 2017...Ps1Catalog.now, .sedan, mm(4720, 1849, 1441, 2875), "235/45R18", ["model 3", "model3", "m3"], .slim) { (t: inout Ps1Tpl) in t.roofF = 0.42; t.roofR = 0.64; t.rw = 0.87 },
        c("tesla-modely", "Tesla", "Model Y", "", 2020...Ps1Catalog.now, .suv, mm(4751, 1921, 1624, 2890), "255/45R19", ["model y", "modely"], .slim) { (t: inout Ps1Tpl) in t.sill = 0.17; t.roofR = 0.80; t.rw = 0.965 },
        c("mg-zs", "MG", "ZS", "", 2017...Ps1Catalog.now, .suv, mm(4323, 1809, 1653, 2585), "215/55R17", ["zs", "zs ev"], .wide, [.roofRails]),
        c("mg-mg4", "MG", "MG4", "", 2022...Ps1Catalog.now, .hatch, mm(4287, 1836, 1504, 2705), "215/55R17", ["mg4", "4"], .slim, [.roofSpoiler]),
        c("suzuki-jimny-jb74", "Suzuki", "Jimny", "JB74 3-door", 2018...Ps1Catalog.now, .fourwdWagon, mm(3480, 1645, 1725, 2250), "195/80R15", ["jimny"], .round, [.spare, .flares]) { (t: inout Ps1Tpl) in t.frontShare = 0.5; t.ws = 0.27; t.roofF = 0.37; t.roofR = 0.97; t.belt = 0.62; t.nose = 0.58 },
        c("suzuki-swift-sport-zc33", "Suzuki", "Swift Sport", "ZC33S", 2018...2024, .hotHatch, mm(3890, 1735, 1495, 2450), "195/45R17", ["swift sport", "swift"], .wide, [.roofSpoiler]) { (t: inout Ps1Tpl) in t.roofR = 0.80 },

        // ---- Jeep, Land Rover, Mercedes, Audi, Porsche, Honda ----
        c("jeep-wrangler-jl4", "Jeep", "Wrangler", "JL 4-door", 2018...Ps1Catalog.now, .fourwdWagon, mm(4882, 1894, 1838, 3008), "255/70R18", ["wrangler", "wrangler unlimited", "wrangler rubicon", "rubicon", "jl"], .sevenSlot, [.spare, .flares, .steps]) { (t: inout Ps1Tpl) in t.ws = 0.31; t.roofF = 0.38; t.roofR = 0.98; t.belt = 0.62 },
        c("jeep-wrangler-jl2", "Jeep", "Wrangler", "JL 2-door", 2018...Ps1Catalog.now, .fourwdWagon, mm(4334, 1894, 1841, 2459), "255/70R18", ["wrangler", "wrangler 2 door", "wrangler sport"], .sevenSlot, [.spare, .flares]) { (t: inout Ps1Tpl) in t.ws = 0.34; t.roofF = 0.42; t.roofR = 0.98; t.belt = 0.62; t.doors = 2; t.longRoof = false },
        c("lr-defender-110", "Land Rover", "Defender", "110 (L663)", 2020...Ps1Catalog.now, .fourwdWagon, mm(4758, 1996, 1967, 3022), "255/60R20", ["defender", "defender 110"], .generic, [.spare, .roofRails]) { (t: inout Ps1Tpl) in t.ws = 0.27; t.roofF = 0.36; t.roofR = 0.98 },
        c("lr-defender-90", "Land Rover", "Defender", "90 (L663)", 2020...Ps1Catalog.now, .fourwdWagon, mm(4323, 1996, 1969, 2587), "255/60R20", ["defender 90", "defender"], .generic, [.spare, .roofRails]) { (t: inout Ps1Tpl) in t.ws = 0.29; t.roofF = 0.39; t.roofR = 0.98; t.doors = 2 },
        c("merc-g-w463a", "Mercedes", "G-Class", "W463", 2018...Ps1Catalog.now, .fourwdWagon, mm(4873, 1984, 1979, 2890), "275/50R20", ["g class", "g wagon", "g63", "g 63", "g500", "g400d"], .round, [.spare, .flares, .steps]) { (t: inout Ps1Tpl) in t.ws = 0.31; t.roofF = 0.40; t.roofR = 0.985; t.belt = 0.62; t.nose = 0.56 },
        c("merc-a-w177", "Mercedes", "A-Class", "W177", 2018...Ps1Catalog.now, .hatch, mm(4419, 1796, 1440, 2729), "225/45R18", ["a class", "a180", "a200", "a250", "a35", "a45", "a45 s"], .wide, [.roofSpoiler]) { (t: inout Ps1Tpl) in t.roofR = 0.78 },
        c("merc-c-w206", "Mercedes", "C-Class", "W206", 2021...Ps1Catalog.now, .sedan, mm(4751, 1820, 1438, 2865), "225/45R18", ["c class", "c200", "c300", "c43", "c63"], .wide) { (t: inout Ps1Tpl) in t.roofR = 0.71 },
        c("audi-rs3-8y", "Audi", "RS3", "8Y sedan", 2021...Ps1Catalog.now, .sedan, mm(4542, 1851, 1412, 2631), "265/30R19", ["rs3", "rs 3", "s3", "a3 sedan"], .singleframe, [.bootLip]),
        c("audi-rs4-b9", "Audi", "RS4 Avant", "B9", 2018...Ps1Catalog.now, .wagon, mm(4781, 1866, 1404, 2826), "265/35R19", ["rs4", "rs 4", "rs4 avant", "a4 avant", "s4 avant"], .singleframe, [.roofRails, .roofSpoiler, .flares]) { (t: inout Ps1Tpl) in t.roofR = 0.93 },
        c("audi-q5-fy", "Audi", "Q5", "FY", 2017...2024, .suv, mm(4663, 1893, 1659, 2819), "235/55R19", ["q5", "sq5"], .singleframe, [.roofRails]),
        c("porsche-911-992", "Porsche", "911", "992", 2019...Ps1Catalog.now, .coupe, mm(4519, 1852, 1298, 2450), "245/35R20", ["911", "carrera", "911 carrera", "911 turbo", "992"], .round, [.rearBar]) { (t: inout Ps1Tpl) in t.frontShare = 0.48; t.roofF = 0.45; t.roofR = 0.60; t.rw = 0.97; t.nose = 0.46 },
        c("porsche-911-gt3-992", "Porsche", "911 GT3", "992", 2021...Ps1Catalog.now, .coupe, mm(4573, 1852, 1279, 2457), "255/35R20", ["gt3", "911 gt3", "gt3 rs"], .round, [.rearBar, .wing]) { (t: inout Ps1Tpl) in t.frontShare = 0.48; t.roofF = 0.45; t.roofR = 0.60; t.rw = 0.97; t.nose = 0.46 },
        c("honda-civic-typer-fl5", "Honda", "Civic Type R", "FL5", 2022...Ps1Catalog.now, .liftback, mm(4595, 1890, 1405, 2735), "265/30R19", ["civic type r", "type r", "fl5"], .wide, [.wing]),
        c("honda-civic-typer-fk8", "Honda", "Civic Type R", "FK8", 2017...2021, .liftback, mm(4557, 1877, 1434, 2700), "245/30R20", ["civic type r", "type r", "fk8"], .wide, [.wing, .scoop, .flares]),
        c("honda-civic-fe", "Honda", "Civic", "FE hatch", 2022...Ps1Catalog.now, .liftback, mm(4560, 1802, 1415, 2735), "235/40R18", ["civic"], .generic),

        // ---- Motorcycles: body only ----
        bike("yamaha-mt", "Yamaha", "MT", .naked, "mt 07", "mt07", "mt 09", "mt09", "mt 10", "mt 03", "xsr"),
        bike("yamaha-r", "Yamaha", "YZF-R", .sportBike, "r1", "r6", "r7", "r3", "yzf r1", "yzf r6", "yzf r7"),
        bike("yamaha-tenere", "Yamaha", "Tenere 700", .adventure, "tenere", "tenere 700", "t7"),
        bike("yamaha-wr", "Yamaha", "WR / YZ", .dirt, "wr250r", "wr450f", "yz250f", "yz450f", "wr", "yz"),
        bike("kawasaki-ninja", "Kawasaki", "Ninja", .sportBike, "ninja", "zx 6r", "zx6r", "zx 10r", "zx10r", "ninja 400", "ninja 650", "h2"),
        bike("kawasaki-z", "Kawasaki", "Z", .naked, "z900", "z650", "z400", "z h2", "z900rs"),
        bike("kawasaki-versys", "Kawasaki", "Versys", .adventure, "versys", "klr", "klr650"),
        bike("kawasaki-kx", "Kawasaki", "KX / KLX", .dirt, "kx", "klx", "kx450", "klx300"),
        bike("honda-cbr", "Honda", "CBR", .sportBike, "cbr", "cbr600rr", "cbr1000rr", "fireblade", "cbr650r", "cbr500r"),
        bike("honda-cb", "Honda", "CB", .naked, "cb650r", "cb1000r", "cb500f", "cb300r", "hornet"),
        bike("honda-africa-twin", "Honda", "Africa Twin", .adventure, "africa twin", "crf1100l", "transalp", "nc750x"),
        bike("honda-goldwing", "Honda", "Gold Wing", .tourer, "gold wing", "goldwing"),
        bike("honda-crf", "Honda", "CRF", .dirt, "crf", "crf250l", "crf450", "crf300l", "xr650l", "xr"),
        bike("honda-scooter", "Honda", "Scooter", .scooter, "pcx", "forza", "adv", "adv350", "sh"),
        bike("honda-rebel", "Honda", "Rebel", .cruiser, "rebel", "shadow"),
        bike("suzuki-gsxr", "Suzuki", "GSX-R", .sportBike, "gsx r", "gsxr", "gsx r1000", "gsx r750", "gsx r600", "hayabusa"),
        bike("suzuki-vstrom", "Suzuki", "V-Strom", .adventure, "v strom", "vstrom", "dr650", "drz400"),
        bike("suzuki-sv", "Suzuki", "SV / GSX-S", .naked, "sv650", "gsx s", "gsx s750", "gsx s1000"),
        bike("bmw-gs", "BMW", "GS", .adventure, "gs", "r1250gs", "r 1250 gs", "r1300gs", "f850gs", "f900gs", "g310gs"),
        bike("bmw-rr", "BMW", "S 1000 RR", .sportBike, "s1000rr", "s 1000 rr", "m1000rr"),
        bike("bmw-rt", "BMW", "RT / K", .tourer, "r1250rt", "rt", "k1600", "k1600gt"),
        bike("bmw-r", "BMW", "R nineT / S 1000 R", .naked, "r nine t", "r ninet", "s1000r", "f900r"),
        bike("ktm-duke", "KTM", "Duke", .naked, "duke", "390 duke", "790 duke", "890 duke", "1290 super duke", "super duke"),
        bike("ktm-adv", "KTM", "Adventure", .adventure, "390 adventure", "890 adventure", "1290 super adventure", "adventure"),
        bike("ktm-rc", "KTM", "RC", .sportBike, "rc 390", "rc390", "rc8"),
        bike("ktm-exc", "KTM", "EXC / SX", .dirt, "exc", "sx", "300 exc", "500 exc", "450 sx", "sx f", "exc f"),
        bike("ducati-panigale", "Ducati", "Panigale", .sportBike, "panigale", "v4", "v2", "panigale v4", "959", "1299", "supersport"),
        bike("ducati-monster", "Ducati", "Monster", .naked, "monster", "streetfighter", "scrambler"),
        bike("ducati-multistrada", "Ducati", "Multistrada", .adventure, "multistrada", "desertx", "desert x"),
        bike("ducati-diavel", "Ducati", "Diavel", .cruiser, "diavel", "xdiavel"),
        bike("triumph-street", "Triumph", "Street / Speed", .naked, "street triple", "speed triple", "trident", "bonneville", "speed twin", "thruxton", "speed 400"),
        bike("triumph-tiger", "Triumph", "Tiger", .adventure, "tiger", "tiger 900", "tiger 1200", "scrambler 1200"),
        bike("triumph-rocket", "Triumph", "Rocket 3", .cruiser, "rocket 3", "rocket"),
        bike("harley", "Harley-Davidson", "Harley", .cruiser, "sportster", "softail", "fat boy", "fatboy", "street bob", "low rider", "breakout", "nightster", "iron 883", "forty eight"),
        bike("harley-touring", "Harley-Davidson", "Touring", .tourer, "road glide", "street glide", "road king", "electra glide", "ultra limited"),
        bike("harley-panamerica", "Harley-Davidson", "Pan America", .adventure, "pan america"),
        bike("indian", "Indian", "Indian", .cruiser, "scout", "chief", "chieftain", "springfield", "super chief"),
        bike("vespa", "Vespa", "Vespa", .scooter, "vespa", "gts", "primavera", "sprint"),
        bike("royal-enfield", "Royal Enfield", "Royal Enfield", .naked, "classic 350", "interceptor", "continental gt", "meteor", "bullet"),
        bike("royal-enfield-himalayan", "Royal Enfield", "Himalayan", .adventure, "himalayan"),
    ]

    private static let byIdMap: [String: Ps1Model] = Dictionary(all.map { ($0.id, $0) }, uniquingKeysWith: { _, newer in newer })

    static func byId(_ id: String?) -> Ps1Model? {
        guard let key: String = id else { return nil }
        return byIdMap[key]
    }

    /// Lower case, words separated by single spaces, punctuation gone.
    static func norm(_ s: String) -> String {
        let spaced: String = s.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: " ", options: .regularExpression)
        return spaced.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func compact(_ s: String) -> String {
        return norm(s).replacingOccurrences(of: " ", with: "")
    }

    private static func makeKey(_ s: String) -> String {
        let c: String = compact(s)
        return compact(makes[c] ?? s)
    }

    /// Whether `alias` appears in `text` as a run of whole words, spacing ignored.
    private static func wordsMatch(_ text: [String], _ alias: String) -> Bool {
        let want: String = compact(alias)
        if want.isEmpty { return false }
        let wantCount: Int = want.count
        for i in 0..<text.count {
            var sb: String = ""
            for j in i..<text.count {
                sb += text[j]
                if sb.count == wantCount && sb == want { return true }
                if sb.count >= wantCount { break }
            }
        }
        return false
    }

    /// How well `m` fits: the longest alias that matched, plus a bonus when the year is in range. Nil = no match.
    private static func score(_ m: Ps1Model, _ make: String, _ model: String, _ year: Int?) -> Int? {
        let mk: String = makeKey(make)
        let modelWords: [String] = norm(model).split(separator: " ").map(String.init).filter { (w: String) -> Bool in !w.isEmpty }
        // Make and model typed together in the model box, e.g. "Ford Focus RS", also counts.
        let makeOk: Bool = !mk.isEmpty && mk == compact(m.make)
        let inModel: Bool = !makeOk && !modelWords.isEmpty && makeKey(modelWords[0]) == compact(m.make)
        if !makeOk && !inModel { return nil }
        let words: [String] = inModel ? Array(modelWords.dropFirst()) : modelWords
        if words.isEmpty { return nil }
        let matched: [Int] = m.aliases.filter { wordsMatch(words, $0) }.map { compact($0).count }
        guard let best: Int = matched.max() else { return nil }
        let yearBonus: Int
        if let y: Int = year {
            yearBonus = m.years.contains(y) ? 50 : -50
        } else {
            yearBonus = m.years.upperBound >= now ? 1 : 0
        }
        return best * 4 + yearBonus
    }

    /// The best catalogue model for what was typed, if any. `kind` limits it to one kind. Ties keep the first entry
    /// (newer generation).
    static func best(make: String, model: String, year: Int?, kind: Ps1Kind? = nil) -> Ps1Model? {
        var bestModel: Ps1Model? = nil
        var bestScore: Int = Int.min
        for m in all {
            if let k: Ps1Kind = kind, m.body.kind != k { continue }
            guard let s: Int = score(m, make, model, year) else { continue }
            if s > bestScore {
                bestScore = s
                bestModel = m
            }
        }
        return bestModel
    }

    /// Every variant worth offering for what was typed: the matches and the rest of their family (i30, i30 N, i30
    /// Sedan...), best first.
    static func candidates(make: String, model: String, year: Int?) -> [Ps1Model] {
        var scored: [(m: Ps1Model, s: Int)] = []
        for m in all {
            if let s: Int = score(m, make, model, year) {
                scored.append((m, s))
            }
        }
        scored.sort { (a: (m: Ps1Model, s: Int), b: (m: Ps1Model, s: Int)) -> Bool in a.s > b.s }
        if scored.isEmpty { return [] }
        let families: Set<String> = Set(scored.map { (e: (m: Ps1Model, s: Int)) -> String in e.m.make + "\u{0}" + e.m.family })
        let scoredIds: Set<String> = Set(scored.map { (e: (m: Ps1Model, s: Int)) -> String in e.m.id })
        let rest: [Ps1Model] = all.filter { (m: Ps1Model) -> Bool in
            families.contains(m.make + "\u{0}" + m.family) && !scoredIds.contains(m.id)
        }
        return scored.map { (e: (m: Ps1Model, s: Int)) -> Ps1Model in e.m } + rest
    }
}
