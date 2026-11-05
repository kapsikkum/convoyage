import Foundation
import CoreLocation
import Observation

enum NavPhase: Equatable {
    case idle
    /// Asking the server for a route.
    case routing
    case navigating
    case arrived
}

/// Turn-by-turn navigation: fetches the route, follows the position along it, speaks the prompts and reroutes.
/// The maths lives in `NavEngine.swift`; this class only wires it to the server, the GPS and the speaker.
@Observable
@MainActor
final class NavigationSession {
    var phase: NavPhase = .idle
    var destination: NavPlace?
    var route: NavRoute?
    var progress: NavProgress?
    /// Shown on the nav screen; cleared by the next successful route or by the person.
    var errorText: String?
    var muted: Bool = false
    var rerouting: Bool = false
    var hazardAhead: (hazard: HazardDto, meters: Double)?
    /// True while the person has asked to see the whole route instead of following their position.
    var overview: Bool = false
    /// Direction the camera faces: the last course while moving.
    var camHeading: Double = 0

    let search: DestinationSearch = DestinationSearch()

    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private let location: LocationService
    @ObservationIgnored private let hazards: HazardStore
    @ObservationIgnored private let voice: NavVoice = NavVoice()
    @ObservationIgnored private var tracker: RouteTracker?
    @ObservationIgnored private var offRoute: OffRouteDetector = OffRouteDetector()
    @ObservationIgnored private var prompts: PromptPlanner = PromptPlanner()
    @ObservationIgnored private var loop: Task<Void, Never>?
    @ObservationIgnored private var lastFixTime: Date?
    @ObservationIgnored private var lastRerouteAt: Date = Date.distantPast
    @ObservationIgnored private var routeTask: Task<Void, Never>?
    /// Profile for the route ("car" or "motorcycle"), read from the saved drive / ride choice.
    @ObservationIgnored var profileProvider: () -> String = { return "car" }
    /// Called when navigation starts or stops (background location follows it).
    @ObservationIgnored var onActiveChange: (() -> Void)?

    init(api: ApiClient, location: LocationService, hazards: HazardStore) {
        self.api = api
        self.location = location
        self.hazards = hazards
    }

    /// A convoy event, through the navigation voice (so it ducks music the same way and respects mute).
    func announce(_ text: String) {
        voice.speak(text)
    }

    func setMuted(_ on: Bool) {
        muted = on
        voice.muted = on
    }

    var isActive: Bool {
        return phase == .routing || phase == .navigating
    }

    var routeCoordinates: [CLLocationCoordinate2D] {
        guard let r = route else { return [] }
        return r.line.points.map { (p: GeoPoint) -> CLLocationCoordinate2D in
            return CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)
        }
    }

    /// The camera target while following; nil in overview or when there is no fix.
    var followCamera: FollowCamera? {
        guard phase == .navigating, !overview, let loc = location.latest else { return nil }
        return FollowCamera(coordinate: loc.coordinate, heading: camHeading)
    }

    var currentStep: NavStep? {
        guard let r = route, let p = progress, p.stepIndex < r.steps.count else { return nil }
        return r.steps[p.stepIndex]
    }

    /// The step after the current manoeuvre, for the "then ..." line.
    var followingStep: NavStep? {
        guard let r = route, let p = progress, p.stepIndex + 1 < r.steps.count else { return nil }
        return r.steps[p.stepIndex + 1]
    }

    // MARK: Start and stop

    /// Picks a destination and fetches the route from where I am.
    func start(to place: NavPlace) {
        guard let here = location.latest else {
            errorText = "Waiting for a GPS fix. Check that location is on."
            return
        }
        destination = place
        errorText = nil
        phase = .routing
        route = nil
        progress = nil
        overview = false
        onActiveChange?()
        routeTask?.cancel()
        routeTask = Task { [weak self] in
            await self?.fetchRoute(from: here.coordinate, to: place.coordinate, isReroute: false)
        }
        startLoop()
    }

    /// Ends navigation and forgets the destination.
    func end() {
        routeTask?.cancel()
        routeTask = nil
        loop?.cancel()
        loop = nil
        voice.stop()
        phase = .idle
        route = nil
        progress = nil
        destination = nil
        tracker = nil
        hazardAhead = nil
        rerouting = false
        overview = false
        errorText = nil
        offRoute.reset()
        prompts.reset()
        onActiveChange?()
    }

    private func fetchRoute(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D, isReroute: Bool) async {
        let result: ApiResult<RouteDto> = await api.route(fromLng: from.longitude, fromLat: from.latitude,
                                                          toLng: to.longitude, toLat: to.latitude,
                                                          profile: profileProvider())
        if Task.isCancelled { return }
        rerouting = false
        switch result {
        case .failure(let f):
            if isReroute {
                // Keep following the old line; try again after the cooldown.
                errorText = "Could not find a new route"
            } else {
                errorText = f.status == 404 ? "No route found to that place" : f.message
                phase = .idle
                destination = nil
                onActiveChange?()
                loop?.cancel()
                loop = nil
            }
        case .ok(let dto):
            guard let nav = NavRouteBuilder.build(dto) else {
                errorText = "The server sent a route the app cannot read"
                if !isReroute {
                    phase = .idle
                    destination = nil
                    onActiveChange?()
                }
                return
            }
            errorText = nil
            route = nav
            if !isReroute, nav.line.points.count > 1 {
                let far: Int = min(nav.line.points.count - 1, 8)
                camHeading = Geo.bearing(nav.line.points[0], nav.line.points[far])
            }
            tracker = RouteTracker(route: nav)
            prompts.reset()
            offRoute.reset()
            progress = nil
            phase = .navigating
            if !isReroute {
                if let first = nav.steps.first(where: { (s: NavStep) -> Bool in return s.type == "depart" }) ?? nav.steps.first {
                    let name: String = first.name.isEmpty ? "" : " on \(first.name)"
                    voice.speak("Starting navigation\(name)")
                }
            } else {
                voice.speak("Route updated")
            }
            if let here = location.latest { process(here) }
        }
    }

    // MARK: Following

    private func startLoop() {
        loop?.cancel()
        loop = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                self?.tick()
            }
        }
    }

    private func tick() {
        guard phase == .navigating, let loc = location.latest else { return }
        // Fixes older than 15 s are not "where I am now".
        if abs(loc.timestamp.timeIntervalSinceNow) > 15 { return }
        process(loc)
    }

    private func process(_ loc: CLLocation) {
        guard phase == .navigating, var t = tracker else { return }
        if loc.course >= 0 && loc.speed > 1 { camHeading = loc.course }
        let here: GeoPoint = GeoPoint(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
        guard let p = t.update(here) else { return }
        tracker = t
        progress = p

        if p.arrived {
            phase = .arrived
            hazardAhead = nil
            voice.speak("You have arrived")
            onActiveChange?()
            return
        }

        // Voice prompts.
        if let r = route, p.stepIndex < r.steps.count, let prompt = prompts.next(stepIndex: p.stepIndex, distance: p.distanceToManeuver) {
            voice.speak(Maneuver.spoken(for: r.steps[p.stepIndex], stage: prompt.stage, meters: prompt.meters))
        }

        // Hazards on the route within a kilometre ahead.
        if let r = route {
            let points: [HazardPoint] = hazards.active.map { (h: HazardDto) -> HazardPoint in
                return HazardPoint(id: h.id, kind: h.kind, subkind: h.subkind, point: GeoPoint(lat: h.lat, lng: h.lng))
            }
            if let found = HazardAhead.nearest(points, on: r.line, along: p.along),
               let dto = hazards.active.first(where: { (h: HazardDto) -> Bool in return h.id == found.hazard.id }) {
                hazardAhead = (hazard: dto, meters: found.ahead)
            } else {
                hazardAhead = nil
            }
        }

        // Off route for more than 8 s: ask for a new route (not more often than every 15 s).
        let accuracy: Double? = loc.horizontalAccuracy >= 0 ? loc.horizontalAccuracy : nil
        let due: Bool = offRoute.update(crossTrack: p.crossTrack, accuracy: accuracy, at: Date())
        if due && !rerouting && Date().timeIntervalSince(lastRerouteAt) > 15, let dest = destination {
            lastRerouteAt = Date()
            rerouting = true
            offRoute.reset()
            let from: CLLocationCoordinate2D = loc.coordinate
            let to: CLLocationCoordinate2D = dest.coordinate
            routeTask?.cancel()
            routeTask = Task { [weak self] in
                await self?.fetchRoute(from: from, to: to, isReroute: true)
            }
        }
    }

    func dismissHazard() {
        hazardAhead = nil
    }
}

/// Builds a `NavRoute` from the server's answer.
enum NavRouteBuilder {
    static func build(_ dto: RouteDto) -> NavRoute? {
        let precision: Int = dto.geometryPrecision ?? 6
        let coords: [CLLocationCoordinate2D] = Polyline.decode(dto.geometry, precision: precision)
        if coords.count < 2 { return nil }
        let points: [GeoPoint] = coords.map { (c: CLLocationCoordinate2D) -> GeoPoint in
            return GeoPoint(lat: c.latitude, lng: c.longitude)
        }
        let steps: [NavStep] = dto.steps.map { (s: RouteStepDto) -> NavStep in
            var loc: GeoPoint? = nil
            if let l = s.maneuver.location, l.count >= 2 { loc = GeoPoint(lat: l[1], lng: l[0]) }
            return NavStep(type: s.maneuver.type, modifier: s.maneuver.modifier, name: s.name,
                           distanceM: s.distanceM, durationS: s.durationS, instruction: s.instruction, location: loc)
        }
        return NavRoute(line: RouteLine(points: points), steps: steps,
                        distanceM: dto.distanceM, durationS: dto.durationS, profile: dto.profile ?? "car")
    }
}
