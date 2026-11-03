import Foundation
import CoreLocation
import Observation

enum RecorderPhase: String, Codable {
    case idle
    case recording
    case paused
    /// Stopped; waiting for (or retrying) the upload.
    case finishing
}

/// One accepted point of the trace.
struct TrackPoint: Codable, Equatable {
    var lat: Double
    var lng: Double
    /// Unix seconds.
    var t: Double
}

/// A photo taken during the ride.
struct PhotoRef: Codable, Identifiable, Equatable {
    /// The media id on the server.
    var id: String
    var lat: Double?
    var lng: Double?
    var takenAt: String
    var attached: Bool
}

/// The in-progress ride, persisted to disk so a crash or a kill does not lose the trace.
struct RideDraft: Codable {
    var rideId: String?
    var startedAt: Date
    var endedAt: Date?
    var title: String
    var visibility: String
    var vehicleId: String?
    var convoyCode: String?
    var phase: RecorderPhase
    var points: [TrackPoint]
    var distanceM: Double
    var movingS: Double
    /// Seconds spent recording (paused time excluded), up to the last pause.
    var activeS: Double
    var maxSpeedMps: Double
    var elevationGainM: Double
    var refAlt: Double?
    var photos: [PhotoRef]
}

enum UploadState: Equatable {
    case uploading
    case failed(String)
    case done
}

/// What the Recap screen shows.
struct RideRecap {
    var title: String
    var startedAt: Date
    var distanceM: Double
    var durationS: Double
    var movingS: Double
    var maxSpeedMps: Double
    var avgSpeedMps: Double
    var elevationGainM: Double
    var coordinates: [CLLocationCoordinate2D]
    var photos: [PhotoRef]
    var rideId: String?
    var visibility: String
    var upload: UploadState
    var posted: Bool
}

/// Foreground GPS ride recorder: start, pause, stop. Filters poor fixes and impossible jumps, computes the stats,
/// persists the draft and uploads with rides create/finish.
@Observable
@MainActor
final class RideRecorder {
    var phase: RecorderPhase = .idle
    var distanceM: Double = 0
    var elapsedS: Double = 0
    var movingS: Double = 0
    var maxSpeedMps: Double = 0
    var elevationGainM: Double = 0
    var photoCount: Int = 0
    /// The latest fix was too inaccurate to use.
    var weakGps: Bool = false
    var recap: RideRecap?
    var showRecap: Bool = false

    @ObservationIgnored private var draft: RideDraft?
    @ObservationIgnored private var segmentStart: Date?
    @ObservationIgnored private var breakNext: Bool = true
    @ObservationIgnored private var lastFixT: Double?
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var creating: Task<String?, Never>?
    @ObservationIgnored private var lastSave: Date = Date()
    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private let location: LocationService

    /// Fixes worse than this are dropped.
    static let maxAccuracyM: Double = 30
    /// A jump implying more than this speed (324 km/h) between accepted fixes is a GPS glitch.
    static let maxJumpMps: Double = 90
    /// Moving slower than this counts as stopped.
    static let movingThresholdMps: Double = 0.8
    /// Smallest step worth storing a new point for.
    static let minStepM: Double = 3
    /// Elevation changes below this are noise.
    static let elevationHysteresisM: Double = 3

    init(api: ApiClient, location: LocationService) {
        self.api = api
        self.location = location
        location.onFix = { [weak self] (loc: CLLocation) in
            self?.handleFix(loc)
        }
    }

    var isActive: Bool { return phase == .recording || phase == .paused }

    var avgSpeedMps: Double {
        return movingS > 1 ? min(distanceM / movingS, 120) : 0
    }

    var currentRideId: String? { return draft?.rideId }

    // MARK: Controls

    func start(vehicleId: String?, convoyCode: String?) {
        guard phase == .idle else { return }
        let now: Date = Date()
        draft = RideDraft(rideId: nil, startedAt: now, endedAt: nil, title: RideRecorder.defaultTitle(now), visibility: "followers",
                          vehicleId: vehicleId, convoyCode: convoyCode, phase: .recording, points: [], distanceM: 0, movingS: 0,
                          activeS: 0, maxSpeedMps: 0, elevationGainM: 0, refAlt: nil, photos: [])
        phase = .recording
        recap = nil
        showRecap = false
        segmentStart = now
        breakNext = true
        lastFixT = nil
        location.start()
        syncStats()
        startTicker()
        persist()
        Task { [weak self] in
            _ = await self?.ensureServerRide()
        }
    }

    func pause() {
        guard phase == .recording else { return }
        closeSegment()
        phase = .paused
        draft?.phase = .paused
        syncStats()
        persist()
    }

    func resume() {
        guard phase == .paused else { return }
        phase = .recording
        draft?.phase = .recording
        segmentStart = Date()
        breakNext = true
        lastFixT = nil
        startTicker()
        persist()
    }

    /// Stops the ride, shows the Recap and uploads in the background.
    func stop() {
        guard isActive else { return }
        if phase == .recording { closeSegment() }
        ticker?.cancel()
        ticker = nil
        draft?.endedAt = Date()
        draft?.phase = .finishing
        phase = .finishing
        syncStats()
        persist()
        recap = makeRecap(upload: .uploading)
        showRecap = true
        Task { [weak self] in
            await self?.upload()
        }
    }

    /// Throws the ride away (also used for a restored draft the person does not want).
    func discard() {
        ticker?.cancel()
        ticker = nil
        draft = nil
        phase = .idle
        recap = nil
        showRecap = false
        deleteFile()
        resetStats()
    }

    /// Done with the Recap: back to idle once the upload succeeded.
    func closeRecap() {
        showRecap = false
        if phase == .finishing, case .done? = recap?.upload {
            phase = .idle
            draft = nil
        }
    }

    func retryUpload() {
        guard phase == .finishing else { return }
        recap = makeRecap(upload: .uploading)
        Task { [weak self] in
            await self?.upload()
        }
    }

    func reopenRecap() {
        if phase == .finishing {
            if recap == nil { recap = makeRecap(upload: .failed("Not uploaded yet")) }
            showRecap = true
        }
    }

    func setTitle(_ title: String) {
        draft?.title = title
        recap?.title = title
        persist()
    }

    func setVisibility(_ visibility: String) {
        draft?.visibility = visibility
        recap?.visibility = visibility
        persist()
    }

    func markPosted() {
        recap?.posted = true
    }

    // MARK: Photos

    /// Records an uploaded photo against the current ride and attaches it on the server when possible.
    func addPhoto(mediaId: String, lat: Double?, lng: Double?, takenAt: Date) async {
        guard draft != nil else { return }
        draft?.photos.append(PhotoRef(id: mediaId, lat: lat, lng: lng, takenAt: Iso.string(takenAt), attached: false))
        photoCount = draft?.photos.count ?? 0
        persist()
        if let rideId = await ensureServerRide() {
            if await attach(mediaId: mediaId, rideId: rideId) {
                markAttached(mediaId)
            }
        }
        recap?.photos = draft?.photos ?? []
    }

    private func attach(mediaId: String, rideId: String) async -> Bool {
        guard let p = draft?.photos.first(where: { (x: PhotoRef) -> Bool in return x.id == mediaId }) else { return false }
        let r: ApiResult<RidePhotoDto> = await api.attachPhoto(rideId: rideId, mediaId: mediaId, lat: p.lat, lng: p.lng, takenAt: p.takenAt)
        if case .ok = r { return true }
        return false
    }

    private func markAttached(_ mediaId: String) {
        guard let idx = draft?.photos.firstIndex(where: { (x: PhotoRef) -> Bool in return x.id == mediaId }) else { return }
        draft?.photos[idx].attached = true
        persist()
    }

    // MARK: Fixes

    private func handleFix(_ loc: CLLocation) {
        guard phase == .recording, draft != nil else { return }
        if loc.horizontalAccuracy < 0 || loc.horizontalAccuracy > RideRecorder.maxAccuracyM {
            weakGps = true
            return
        }
        weakGps = false
        if abs(loc.timestamp.timeIntervalSinceNow) > 15 { return }

        let t: Double = loc.timestamp.timeIntervalSince1970
        let speed: Double? = loc.speed >= 0 ? min(loc.speed, 120) : nil
        var accept: Bool = false
        var addDistance: Double = 0

        if let last = draft?.points.last, !breakNext {
            let dt: Double = t - last.t
            if dt <= 0 { return }
            let step: Double = CLLocation(latitude: last.lat, longitude: last.lng).distance(from: loc)
            // An impossible jump: drop the fix, keep the trace as it was.
            if step / dt > RideRecorder.maxJumpMps { return }
            let v: Double = speed ?? (step / dt)
            if let prev = lastFixT {
                let gap: Double = t - prev
                if gap > 0 && gap <= 15 && v > RideRecorder.movingThresholdMps {
                    draft?.movingS += gap
                }
            }
            if step >= RideRecorder.minStepM || dt >= 10 {
                accept = true
                addDistance = step
            }
        } else {
            // First fix of the ride, or the first one after a pause: no distance across the gap.
            accept = true
            breakNext = false
        }
        lastFixT = t

        if let s = speed, loc.speedAccuracy < 0 || loc.speedAccuracy <= 3 {
            if s > (draft?.maxSpeedMps ?? 0) { draft?.maxSpeedMps = s }
        }
        if loc.verticalAccuracy > 0 && loc.verticalAccuracy <= 15 {
            addElevation(loc.altitude)
        }
        if accept {
            draft?.points.append(TrackPoint(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude, t: t))
            draft?.distanceM += addDistance
        }
        syncStats()
        if Date().timeIntervalSince(lastSave) > 15 { persist() }
    }

    private func addElevation(_ alt: Double) {
        guard let ref = draft?.refAlt else {
            draft?.refAlt = alt
            return
        }
        if alt - ref > RideRecorder.elevationHysteresisM {
            draft?.elevationGainM += alt - ref
            draft?.refAlt = alt
        } else if ref - alt > RideRecorder.elevationHysteresisM {
            draft?.refAlt = alt
        }
    }

    // MARK: Time

    private func closeSegment() {
        if let s = segmentStart {
            draft?.activeS += Date().timeIntervalSince(s)
        }
        segmentStart = nil
    }

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { break }
                self?.syncStats()
            }
        }
    }

    private func syncStats() {
        guard let d = draft else { return }
        distanceM = d.distanceM
        movingS = d.movingS
        maxSpeedMps = d.maxSpeedMps
        elevationGainM = d.elevationGainM
        photoCount = d.photos.count
        var e: Double = d.activeS
        if let s = segmentStart { e += Date().timeIntervalSince(s) }
        elapsedS = e
    }

    private func resetStats() {
        distanceM = 0
        elapsedS = 0
        movingS = 0
        maxSpeedMps = 0
        elevationGainM = 0
        photoCount = 0
        weakGps = false
    }

    // MARK: Server

    private func ensureServerRide() async -> String? {
        if let id = draft?.rideId { return id }
        if let running = creating { return await running.value }
        let work: Task<String?, Never> = Task { await self.createServerRide() }
        creating = work
        let id: String? = await work.value
        creating = nil
        return id
    }

    private func createServerRide() async -> String? {
        guard let d = draft else { return nil }
        var body: [String: Any] = ["startedAt": Iso.string(d.startedAt), "visibility": d.visibility]
        if let p = d.points.first {
            body["startLat"] = p.lat
            body["startLng"] = p.lng
        } else if let loc = location.latest {
            body["startLat"] = loc.coordinate.latitude
            body["startLng"] = loc.coordinate.longitude
        }
        if let v = d.vehicleId { body["vehicleId"] = v }
        if let c = d.convoyCode { body["convoyCode"] = c }
        if !d.title.isEmpty { body["title"] = d.title }
        let r: ApiResult<RideDto> = await api.startRide(body)
        if case .ok(let ride) = r {
            draft?.rideId = ride.id
            recap?.rideId = ride.id
            persist()
            return ride.id
        }
        return nil
    }

    /// Creates the ride if needed, attaches waiting photos, finishes it with the trace.
    private func upload() async {
        guard let d = draft, let endedAt = d.endedAt else { return }
        guard let rideId = await ensureServerRide() else {
            fail("Cannot reach the server. The ride is saved on this phone; try again when you are online.")
            return
        }
        // Photos first, so a failure there does not leave a finished ride with photos missing.
        for p in d.photos where !p.attached {
            if await attach(mediaId: p.id, rideId: rideId) { markAttached(p.id) }
        }
        let latest: RideDraft = draft ?? d
        let coords: [CLLocationCoordinate2D] = latest.points.map { (p: TrackPoint) -> CLLocationCoordinate2D in
            return CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)
        }
        let moving: Double = latest.movingS
        let avg: Double = moving > 1 ? min(latest.distanceM / moving, 120) : 0
        var body: [String: Any] = [
            "endedAt": Iso.string(endedAt),
            "distanceM": min(max(latest.distanceM, 0), 20_000_000),
            "durationS": Int(min(max(latest.activeS, 0), 604_800)),
            "maxSpeedMps": min(max(latest.maxSpeedMps, 0), 120),
            "avgSpeedMps": avg,
            "elevationGainM": min(max(latest.elevationGainM, 0), 20_000),
            "polyline": Polyline.encode(coords, precision: 5),
            "polylinePrecision": 5,
            "visibility": latest.visibility
        ]
        if !latest.title.isEmpty { body["title"] = latest.title }
        let r: ApiResult<RideDto> = await api.finishRide(id: rideId, body)
        switch r {
        case .ok:
            succeed()
        case .failure(let f):
            if f.status == 409 {
                succeed() // already finished by an earlier attempt whose answer we never saw
            } else if f.isUnauthorized {
                fail("Sign in again to upload this ride. It is saved on this phone.")
            } else if f.isNetwork {
                fail("Cannot reach the server. The ride is saved on this phone; try again when you are online.")
            } else {
                fail(f.message)
            }
        }
    }

    private func succeed() {
        recap = makeRecap(upload: .done)
        deleteFile()
        if !showRecap {
            phase = .idle
            draft = nil
        }
    }

    private func fail(_ message: String) {
        recap = makeRecap(upload: .failed(message))
    }

    private func makeRecap(upload: UploadState) -> RideRecap? {
        guard let d = draft else { return nil }
        let coords: [CLLocationCoordinate2D] = d.points.map { (p: TrackPoint) -> CLLocationCoordinate2D in
            return CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)
        }
        let avg: Double = d.movingS > 1 ? min(d.distanceM / d.movingS, 120) : 0
        return RideRecap(title: d.title, startedAt: d.startedAt, distanceM: d.distanceM, durationS: d.activeS, movingS: d.movingS,
                         maxSpeedMps: d.maxSpeedMps, avgSpeedMps: avg, elevationGainM: d.elevationGainM, coordinates: coords,
                         photos: d.photos, rideId: d.rideId, visibility: d.visibility, upload: upload, posted: recap?.posted ?? false)
    }

    // MARK: Disk

    private static var fileURL: URL {
        let base: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("active-ride.json")
    }

    /// Writes the draft to disk. Also called when the app goes to the background.
    func persist() {
        lastSave = Date()
        guard let d = draft else { return }
        if case .done? = recap?.upload { return } // uploaded: the file is gone for good
        let url: URL = RideRecorder.fileURL
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(d) {
            try? data.write(to: url, options: .atomic)
        }
    }

    private func deleteFile() {
        try? FileManager.default.removeItem(at: RideRecorder.fileURL)
    }

    /// Picks up a ride that was in progress when the app was killed. A recording comes back paused.
    func restoreIfAny() {
        guard phase == .idle,
              let data = try? Data(contentsOf: RideRecorder.fileURL),
              var d = try? JSONDecoder().decode(RideDraft.self, from: data) else { return }
        if d.phase == .recording { d.phase = .paused }
        draft = d
        phase = d.phase
        breakNext = true
        lastFixT = nil
        segmentStart = nil
        syncStats()
        if d.phase == .finishing {
            recap = makeRecap(upload: .failed("This ride was not uploaded yet."))
        }
    }

    static func defaultTitle(_ date: Date) -> String {
        let hour: Int = Calendar.current.component(.hour, from: date)
        if hour < 5 { return "Night ride" }
        if hour < 12 { return "Morning ride" }
        if hour < 18 { return "Afternoon ride" }
        return "Evening ride"
    }
}
