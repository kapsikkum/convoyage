import SwiftUI
import CoreLocation

/// The recorded route drawn on a plain grid (no map tiles), start in green, finish in pink, photo spots in cyan.
struct RouteSketch: View {
    let coordinates: [CLLocationCoordinate2D]
    var markers: [CLLocationCoordinate2D] = []
    @Environment(\.theme) private var theme

    var body: some View {
        Canvas { context, size in
            // Grid backdrop.
            var grid: Path = Path()
            var x: CGFloat = 0
            while x <= size.width {
                grid.move(to: CGPoint(x: x, y: 0))
                grid.addLine(to: CGPoint(x: x, y: size.height))
                x += 32
            }
            var y: CGFloat = 0
            while y <= size.height {
                grid.move(to: CGPoint(x: 0, y: y))
                grid.addLine(to: CGPoint(x: size.width, y: y))
                y += 32
            }
            context.stroke(grid, with: .color(theme.line.opacity(0.5)), lineWidth: 1)

            guard coordinates.count >= 2 else { return }
            var minLat: Double = 90
            var maxLat: Double = -90
            var minLng: Double = 180
            var maxLng: Double = -180
            for c in coordinates {
                minLat = min(minLat, c.latitude)
                maxLat = max(maxLat, c.latitude)
                minLng = min(minLng, c.longitude)
                maxLng = max(maxLng, c.longitude)
            }
            let midLat: Double = (minLat + maxLat) / 2
            let lngScale: Double = cos(midLat * Double.pi / 180)
            let spanX: Double = max((maxLng - minLng) * lngScale, 0.0005)
            let spanY: Double = max(maxLat - minLat, 0.0005)
            let pad: Double = 24
            let availW: Double = Double(size.width) - pad * 2
            let availH: Double = Double(size.height) - pad * 2
            let scale: Double = min(availW / spanX, availH / spanY)
            let offX: Double = (Double(size.width) - spanX * scale) / 2
            let offY: Double = (Double(size.height) - spanY * scale) / 2

            func project(_ c: CLLocationCoordinate2D) -> CGPoint {
                let px: Double = offX + (c.longitude - minLng) * lngScale * scale
                let py: Double = offY + (maxLat - c.latitude) * scale
                return CGPoint(x: px, y: py)
            }

            var route: Path = Path()
            route.move(to: project(coordinates[0]))
            for c in coordinates.dropFirst() {
                route.addLine(to: project(c))
            }
            context.stroke(route, with: .color(theme.signal),
                           style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))

            for m in markers {
                let p: CGPoint = project(m)
                context.fill(Path(ellipseIn: CGRect(x: p.x - 5, y: p.y - 5, width: 10, height: 10)), with: .color(theme.ink))
                context.fill(Path(ellipseIn: CGRect(x: p.x - 3, y: p.y - 3, width: 6, height: 6)), with: .color(theme.signal))
            }
            let start: CGPoint = project(coordinates[0])
            let end: CGPoint = project(coordinates[coordinates.count - 1])
            context.fill(Path(ellipseIn: CGRect(x: start.x - 7, y: start.y - 7, width: 14, height: 14)), with: .color(theme.go))
            context.fill(Path(ellipseIn: CGRect(x: end.x - 7, y: end.y - 7, width: 14, height: 14)), with: .color(theme.pulse))
        }
        .background(theme.void)
        .cvPanel(fill: theme.void, edge: theme.lineStrong, cut: Cut.l)
    }
}

/// Ride recap: route line, stats and "Post to feed".
struct RecapView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var title: String = ""
    @State private var caption: String = ""
    @State private var visibility: String = "followers"
    @State private var posting: Bool = false
    @State private var postError: String?
    @State private var confirmDiscard: Bool = false
    @State private var seeded: Bool = false

    var body: some View {
        let rec: RideRecorder = app.recorder
        let visBinding: Binding<String> = Binding<String>(
            get: { return visibility },
            set: { (v: String) in
                visibility = v
                rec.setVisibility(v)
            }
        )
        ScrollView {
            if let recap = rec.recap {
                content(recap, rec, visBinding)
            } else {
                Text("No ride to show.")
                    .font(CvFont.text(15))
                    .foregroundStyle(theme.inkMuted)
                    .padding(24)
            }
        }
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .onAppear {
            if !seeded, let r = rec.recap {
                seeded = true
                title = r.title
                visibility = r.visibility
            }
        }
        .confirmationDialog("Discard this ride?", isPresented: $confirmDiscard, titleVisibility: .visible) {
            Button("Discard ride", role: .destructive) {
                rec.discard()
                dismiss()
            }
            Button("Keep it", role: .cancel) {}
        }
    }

    @ViewBuilder
    private func content(_ recap: RideRecap, _ rec: RideRecorder, _ visBinding: Binding<String>) -> some View {
        let isDone: Bool = (recap.upload == .done)
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    SectionLabel(text: "Ride recap")
                    Text(recap.startedAt.formatted(date: .abbreviated, time: .shortened))
                        .font(CvFont.text(13))
                        .foregroundStyle(theme.inkMuted)
                }
                Spacer()
            }

            CvField(placeholder: "Name this ride", text: $title, autocap: .sentences)
                .onChange(of: title) { (_: String, newValue: String) in
                    rec.setTitle(newValue)
                }

            RouteSketch(coordinates: recap.coordinates, markers: recap.photos.compactMap { (p: PhotoRef) -> CLLocationCoordinate2D? in
                guard let la = p.lat, let ln = p.lng else { return nil }
                return CLLocationCoordinate2D(latitude: la, longitude: ln)
            })
            .frame(height: 240)

            stats(recap)

            if !recap.photos.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    SectionLabel(text: "\(recap.photos.count) photo\(recap.photos.count == 1 ? "" : "s")")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(recap.photos) { (p: PhotoRef) in
                                RemoteImage(path: "/v1/media/\(p.id)/thumb")
                                    .frame(width: 120, height: 120)
                                    .clipShape(ChamferShape(cut: Cut.s))
                            }
                        }
                    }
                }
            }

            uploadBanner(recap, rec)

            if isDone {
                VStack(alignment: .leading, spacing: 10) {
                    SectionLabel(text: "Share")
                    CvField(placeholder: "Say something about the ride (optional)", text: $caption, autocap: .sentences)
                    Segmented(items: ["followers", "public"], title: { (v: String) -> String in
                        return v == "public" ? "Public" : "Followers"
                    }, selection: visBinding)
                    if let e = postError {
                        Banner(title: e, kind: .pulse, icon: "exclamationmark.triangle")
                    }
                    if recap.posted {
                        Banner(title: "Posted to your feed", kind: .go, icon: "checkmark.circle")
                    } else {
                        CvButton(title: posting ? "Posting..." : "Post to feed", icon: "paperplane.fill") {
                            postToFeed(recap, rec)
                        }
                        .opacity(posting ? 0.5 : 1)
                        .disabled(posting)
                    }
                }
            }

            CvButton(title: isDone ? "Done" : "Close", kind: .ghost) {
                finish(recap, rec, isDone)
            }
            if case .failed = recap.upload {
                CvButton(title: "Discard ride", kind: .ghost) { confirmDiscard = true }
            }
        }
        .padding(20)
    }

    private func stats(_ r: RideRecap) -> some View {
        let d = Formatters.distance(r.distanceM)
        return LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 16) {
            Readout(caption: "Distance", value: d.value, unit: d.unit, size: 24)
            Readout(caption: "Time", value: Formatters.duration(r.durationS), size: 24)
            Readout(caption: "Moving", value: Formatters.duration(r.movingS), size: 24)
            Readout(caption: "Avg speed", value: "\(Formatters.kmh(r.avgSpeedMps))", unit: "km/h", size: 24)
            Readout(caption: "Max speed", value: "\(Formatters.kmh(r.maxSpeedMps))", unit: "km/h", size: 24)
            Readout(caption: "Climb", value: "\(Int(r.elevationGainM.rounded()))", unit: "m", size: 24)
        }
        .padding(16)
        .cvPanel(fill: theme.surface200, edge: theme.line)
    }

    @ViewBuilder
    private func uploadBanner(_ recap: RideRecap, _ rec: RideRecorder) -> some View {
        switch recap.upload {
        case .uploading:
            Banner(title: "Uploading your ride...", kind: .info, icon: "arrow.up.circle")
        case .failed(let message):
            Banner(title: "Ride not uploaded", subtitle: message, kind: .hazard, icon: "exclamationmark.triangle",
                   actionTitle: "Retry", action: { rec.retryUpload() })
        case .done:
            Banner(title: "Ride saved", kind: .go, icon: "checkmark.circle")
        }
    }

    /// Pushes a title or visibility edited after the upload to the server.
    private func syncRide(_ recap: RideRecap) async {
        guard let id = recap.rideId else { return }
        let clean: String = title.trimmingCharacters(in: .whitespacesAndNewlines)
        var body: [String: Any] = ["visibility": visibility]
        if !clean.isEmpty { body["title"] = String(clean.prefix(120)) }
        _ = await app.api.updateRide(id: id, body)
    }

    private func postToFeed(_ recap: RideRecap, _ rec: RideRecorder) {
        guard let rideId = recap.rideId else { return }
        posting = true
        postError = nil
        Task {
            await syncRide(recap)
            var body: [String: Any] = ["kind": "ride", "rideId": rideId, "visibility": visibility]
            let text: String = caption.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { body["body"] = text }
            let r: ApiResult<PostDto> = await app.api.createPost(body)
            posting = false
            switch r {
            case .ok(let post):
                app.feed.prepend(post)
                rec.markPosted()
            case .failure(let f):
                postError = f.message
            }
        }
    }

    private func finish(_ recap: RideRecap, _ rec: RideRecorder, _ isDone: Bool) {
        if isDone {
            Task {
                await syncRide(recap)
                rec.closeRecap()
                dismiss()
            }
        } else {
            rec.closeRecap()
            dismiss()
        }
    }
}
