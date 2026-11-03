import Foundation
import Observation

/// A named choice from the garage catalogue (the server stores only the id).
struct CatalogItem: Identifiable, Hashable {
    let id: String
    let name: String
}

enum GarageCatalog {
    static let kinds: [CatalogItem] = [
        CatalogItem(id: "car", name: "Car"),
        CatalogItem(id: "motorcycle", name: "Bike"),
        CatalogItem(id: "ute", name: "Ute"),
        CatalogItem(id: "4wd", name: "4WD")
    ]

    static let finishes: [CatalogItem] = [
        CatalogItem(id: "gloss", name: "Gloss"),
        CatalogItem(id: "satin", name: "Satin"),
        CatalogItem(id: "matte", name: "Matte"),
        CatalogItem(id: "metallic", name: "Metallic"),
        CatalogItem(id: "pearl", name: "Pearl")
    ]

    /// Paint swatches: (name, #RRGGBB). Same set as the garage mockup.
    static let paints: [(name: String, hex: String)] = [
        (name: "Pearl white", hex: "#e9edf2"),
        (name: "Emotional red", hex: "#c8102e"),
        (name: "Precious black", hex: "#17191c"),
        (name: "Platinum grey", hex: "#9aa3ad"),
        (name: "Sonic blue", hex: "#2a5bd7"),
        (name: "Acid lime", hex: "#b7e33b"),
        (name: "Signal cyan", hex: "#2de2e6"),
        (name: "Sunset orange", hex: "#ff7a1a")
    ]

    static let wheels: [CatalogItem] = [
        CatalogItem(id: "stock", name: "Stock"),
        CatalogItem(id: "forged_5spoke", name: "Forged 5-spoke"),
        CatalogItem(id: "mesh_gold", name: "Mesh gold"),
        CatalogItem(id: "deep_dish", name: "Deep dish"),
        CatalogItem(id: "steelie", name: "Steelie")
    ]

    static let decals: [CatalogItem] = [
        CatalogItem(id: "stripe_dual", name: "Dual stripe"),
        CatalogItem(id: "race_number", name: "Race number"),
        CatalogItem(id: "sponsor_strip", name: "Sponsor strip"),
        CatalogItem(id: "side_flames", name: "Side flames")
    ]

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
            decals.append(["id": item.id, "slot": "body", "color": "#2de2e6"])
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
