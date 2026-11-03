import Foundation
import Observation

/// A named choice from the garage catalogue (the server stores only the id).
struct CatalogItem: Identifiable, Hashable {
    let id: String
    let name: String
    var slot: String = "body"
}

enum GarageCatalog {
    static let kinds: [CatalogItem] = [
        CatalogItem(id: "car", name: "Car"),
        CatalogItem(id: "motorcycle", name: "Bike"),
        CatalogItem(id: "ute", name: "Ute"),
        CatalogItem(id: "4wd", name: "4WD")
    ]

    static var finishes: [CatalogItem] = [
        CatalogItem(id: "gloss", name: "Gloss"),
        CatalogItem(id: "satin", name: "Satin"),
        CatalogItem(id: "matte", name: "Matte"),
        CatalogItem(id: "metallic", name: "Metallic"),
        CatalogItem(id: "pearl", name: "Pearl")
    ]

    /// Paint swatches: (name, #RRGGBB). The server's catalogue replaces these through `apply`.
    static var paints: [(name: String, hex: String)] = [
        (name: "Convoy Cyan", hex: "#2de2e6"),
        (name: "Pearl White", hex: "#f4f1ea"),
        (name: "Black", hex: "#0b0b0d"),
        (name: "Race Red", hex: "#c8102e"),
        (name: "Cobalt Blue", hex: "#1f4fd8"),
        (name: "Forest Green", hex: "#1e7b45"),
        (name: "Solar Yellow", hex: "#f2c230"),
        (name: "Silver", hex: "#b8bcc2")
    ]

    /// The six wheel looks the renderer draws, keyed by the server's catalogue ids.
    static var wheels: [CatalogItem] = [
        CatalogItem(id: "stock", name: "Stock"),
        CatalogItem(id: "sport5", name: "Sport 5-Spoke"),
        CatalogItem(id: "mesh", name: "Mesh"),
        CatalogItem(id: "split_spoke", name: "Split Spoke"),
        CatalogItem(id: "deep_dish", name: "Deep Dish"),
        CatalogItem(id: "steelie", name: "Steel Wheel")
    ]

    static var decals: [CatalogItem] = [
        CatalogItem(id: "twin_stripe", name: "Twin Stripe", slot: "body"),
        CatalogItem(id: "racing_number", name: "Racing Number", slot: "door"),
        CatalogItem(id: "flames", name: "Flames", slot: "side"),
        CatalogItem(id: "hood_stripe", name: "Hood Stripe", slot: "hood"),
        CatalogItem(id: "sponsor_band", name: "Sponsor Band", slot: "windscreen"),
        CatalogItem(id: "checker_flag", name: "Chequered Flag", slot: "rear")
    ]

    /// Replaces the lists with what `GET /v1/catalogue` says. Lists the server leaves out keep their defaults.
    static func apply(_ c: CatalogueDto) {
        if let list = c.paints, !list.isEmpty {
            var out: [(name: String, hex: String)] = []
            for p in list {
                guard let hex = p.hex else { continue }
                out.append((name: p.name ?? hex, hex: hex))
            }
            if !out.isEmpty { paints = out }
        }
        if let list = c.wheels, !list.isEmpty {
            wheels = list.map { (e: CatalogueDto.Entry) -> CatalogItem in return CatalogItem(id: e.id, name: e.name ?? e.id) }
        }
        if let list = c.decals, !list.isEmpty {
            decals = list.map { (e: CatalogueDto.Entry) -> CatalogItem in return CatalogItem(id: e.id, name: e.name ?? e.id, slot: e.slot ?? "body") }
        }
        if let list = c.finishes, !list.isEmpty {
            finishes = list.map { (e: CatalogueDto.Entry) -> CatalogItem in return CatalogItem(id: e.id, name: e.name ?? e.id) }
        }
    }

    static func name(of id: String?, in list: [CatalogItem]) -> String {
        guard let id = id else { return "" }
        return list.first(where: { (c: CatalogItem) -> Bool in return c.id == id })?.name ?? id
    }
}

/// The editable fields of a vehicle.
struct VehicleDraft {
    var kind: String = "car"
    var make: String = ""
    var model: String = ""
    var yearText: String = ""
    var nickname: String = ""
    var paint: String = GarageCatalog.paints[0].hex
    var finish: String = "gloss"
    var wheel: String = "stock"
    var rideHeightMm: Int = 0
    var decalIds: Set<String> = []
    var makePrimary: Bool = false

    init() {}

    init(from v: VehicleDto) {
        kind = v.kind
        make = v.make
        model = v.model
        yearText = v.year.map { (y: Int) -> String in return String(y) } ?? ""
        nickname = v.nickname ?? ""
        paint = v.build?.paint ?? GarageCatalog.paints[0].hex
        finish = v.build?.finish ?? "gloss"
        wheel = v.build?.wheel ?? "stock"
        rideHeightMm = v.build?.rideHeightMm ?? 0
        decalIds = Set((v.build?.decals ?? []).map { (d: DecalDto) -> String in return d.id })
    }

    var isValid: Bool {
        return !make.trimmingCharacters(in: .whitespaces).isEmpty && !model.trimmingCharacters(in: .whitespaces).isEmpty
    }

    func body(creating: Bool) -> [String: Any] {
        var b: [String: Any] = [
            "kind": kind,
            "make": String(make.trimmingCharacters(in: .whitespaces).prefix(60)),
            "model": String(model.trimmingCharacters(in: .whitespaces).prefix(60)),
            "nickname": String(nickname.trimmingCharacters(in: .whitespaces).prefix(60))
        ]
        if let y = Int(yearText.trimmingCharacters(in: .whitespaces)), y >= 1885, y <= 2028 {
            b["year"] = y
        } else if !creating {
            b["year"] = NSNull()
        }
        var build: [String: Any] = ["paint": paint, "finish": finish, "wheel": wheel, "rideHeightMm": rideHeightMm]
        var decals: [[String: Any]] = []
        for item in GarageCatalog.decals where decalIds.contains(item.id) {
            decals.append(["id": item.id, "slot": item.slot, "color": "#2de2e6"])
        }
        build["decals"] = decals
        b["build"] = build
        if creating && makePrimary { b["isPrimary"] = true }
        return b
    }
}

/// The signed-in person's garage (`/v1/vehicles`).
@Observable
@MainActor
final class GarageStore {
    var vehicles: [VehicleDto] = []
    var loading: Bool = false
    var loaded: Bool = false
    var error: String?

    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private var catalogueLoaded: Bool = false

    init(api: ApiClient) {
        self.api = api
    }

    /// The primary, unsold vehicle.
    var primary: VehicleDto? {
        return vehicles.first(where: { (v: VehicleDto) -> Bool in return (v.isPrimary ?? false) && v.soldAt == nil })
    }

    func load() async {
        loading = true
        let r: ApiResult<[VehicleDto]> = await api.vehicles()
        loading = false
        switch r {
        case .ok(let list):
            vehicles = list
            loaded = true
            error = nil
        case .failure(let f):
            if !f.isUnauthorized { error = f.message }
        }
    }

    /// The garage catalogue (paints, finishes, wheels, decals) into `GarageCatalog`; once per launch.
    func loadCatalogue() async {
        if catalogueLoaded { return }
        catalogueLoaded = true
        let r: ApiResult<CatalogueDto> = await api.catalogue()
        switch r {
        case .ok(let c): GarageCatalog.apply(c)
        case .failure: catalogueLoaded = false
        }
    }

    func clear() {
        vehicles = []
        loaded = false
        error = nil
    }

    /// Creates (`id` nil) or updates a vehicle. Returns an error message, or nil on success.
    func save(id: String?, draft: VehicleDraft) async -> String? {
        let r: ApiResult<VehicleDto>
        if let id = id {
            r = await api.updateVehicle(id: id, draft.body(creating: false))
        } else {
            r = await api.createVehicle(draft.body(creating: true))
        }
        switch r {
        case .ok:
            await load()
            return nil
        case .failure(let f):
            return f.message
        }
    }

    func setPrimary(id: String) async -> String? {
        return await simple(await api.makePrimary(id: id))
    }

    func markSold(id: String) async -> String? {
        return await simple(await api.markSold(id: id))
    }

    func delete(id: String) async -> String? {
        let r: ApiResult<Empty> = await api.deleteVehicle(id: id)
        switch r {
        case .ok:
            await load()
            return nil
        case .failure(let f):
            return f.message
        }
    }

    private func simple(_ r: ApiResult<VehicleDto>) async -> String? {
        switch r {
        case .ok:
            await load()
            return nil
        case .failure(let f):
            return f.message
        }
    }
}
