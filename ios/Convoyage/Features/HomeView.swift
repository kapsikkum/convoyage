import SwiftUI

struct HomeView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var showJoin: Bool = false
    @State private var showCreate: Bool = false
    @State private var openEvent: PsEvent?
    @State private var code: String = ""

    private var greeting: String {
        let hour: Int = Calendar.current.component(.hour, from: Date())
        if hour < 5 { return "Late one" }
        if hour < 12 { return "Morning" }
        if hour < 18 { return "Afternoon" }
        if hour < 22 { return "Evening" }
        return "Late one"
    }

    var body: some View {
        let vm: ConvoyViewModel = app.convoy
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        SectionLabel(text: vm.socketState == .open ? "Connected" : "Offline")
                        Text("\(greeting), \(Formatters.shownName(app.displayName))")
                            .font(CvFont.display(28))
                    }
                    Spacer()
                    CvAvatar(photoURL: app.me?.avatarPath, vehicle: app.myVehicleBuild, initials: Formatters.initials(app.displayName), size: 44)
                }

                convoyCard(vm)

                HStack(spacing: 10) {
                    CvButton(title: "Convoy") { app.tab = .convoy }
                    CvButton(title: "Join", kind: .ghost) { showJoin = true }
                    CvButton(title: app.nav.isActive ? "Navigating" : "Navigate", icon: "location.north.fill", kind: .ghost) {
                        app.showNav = true
                    }
                }

                VStack(alignment: .leading, spacing: 10) {
                    SectionLabel(text: "Events")
                    eventsSection()
                }

                CvButton(title: app.recorder.isActive ? "Open ride mode" : "Record a ride", icon: "record.circle", kind: .ghost) {
                    app.showRide = true
                }

                Button {
                    app.tab = .garage
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        SectionLabel(text: "Your garage")
                        if let v = app.garage.primary {
                            Text(v.title).font(CvFont.display(20))
                            Text(v.subtitle).font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
                        } else {
                            Text("No cars yet").font(CvFont.display(20))
                            Text("Add one in the Garage tab.").font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle(theme.ink)
                    .cvPanel(fill: Color.clear, edge: theme.lineStrong)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 120)
        }
        .background(theme.surface100.ignoresSafeArea())
        .alert("Join a convoy", isPresented: $showJoin) {
            TextField("Invite code", text: $code)
                .textInputAutocapitalization(.characters)
            Button("Join") {
                vm.joinConvoy(code: code)
                code = ""
                app.tab = .convoy
            }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $showCreate) {
            CreateConvoySheet(onCreated: { app.tab = .convoy })
                .presentationDetents([.medium, .large])
        }
        .sheet(item: $openEvent) { (e: PsEvent) in
            EventSheet(event: e)
                .presentationDetents([.medium, .large])
        }
        .task {
            await app.photoscout.refreshStatus()
            if let loc = app.convoy.location.latest {
                await app.photoscout.loadHomeEvents(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
            }
        }
    }

    @ViewBuilder
    private func eventsSection() -> some View {
        let ps: PhotoScoutLayers = app.photoscout
        if ps.available {
            if ps.homeEvents.isEmpty {
                Text("No upcoming events within 150 km.").font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
            }
            ForEach(ps.homeEvents) { (e: PsEvent) in
                Button { openEvent = e } label: {
                    eventRow(e)
                }
                .buttonStyle(.plain)
            }
        } else if ps.configured {
            Text("Link PhotoScout to see meets and cruises near you.").font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
            PhotoScoutConnectButton()
        } else {
            Text("Events come from PhotoScout, which this server does not have.").font(CvFont.text(13)).foregroundStyle(theme.inkMuted)
        }
    }

    private func eventRow(_ e: PsEvent) -> some View {
        let d: Date? = ConditionSheet.parse(e.startTime)
        let dayF: DateFormatter = DateFormatter()
        dayF.dateFormat = "EEE"
        let dateF: DateFormatter = DateFormatter()
        dateF.dateFormat = "dd"
        let day: String = d.map { dayF.string(from: $0).uppercased() } ?? "--"
        let date: String = d.map { dateF.string(from: $0) } ?? "--"
        var meta: String = e.venue ?? e.address ?? ""
        if e.cruise != nil { meta = meta.isEmpty ? "Cruise" : "Cruise \u{00B7} " + meta }
        return placeholderRow(day: day, date: date, title: e.title, meta: meta)
    }

    @ViewBuilder
    private func convoyCard(_ vm: ConvoyViewModel) -> some View {
        if let room = vm.room {
            Button {
                app.tab = .convoy
            } label: {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        HStack(spacing: 8) {
                            Circle().fill(theme.signal).frame(width: 8, height: 8)
                            Text("LIVE CONVOY").font(CvFont.label(11)).tracking(1.6).foregroundStyle(theme.signal)
                        }
                        Spacer()
                        Text(room.code).font(CvFont.mono(12, weight: .medium)).foregroundStyle(theme.inkMuted)
                    }
                    Text(room.name).font(CvFont.display(22))
                    HStack {
                        Readout(caption: "Online", value: "\(vm.onlineCount)", unit: "/\(room.members.count)", size: 22)
                        Spacer()
                        if let spread = vm.spreadMeters {
                            let d = Formatters.distance(spread)
                            Readout(caption: "Spread", value: d.value, unit: d.unit, size: 22)
                        } else {
                            Readout(caption: "Spread", value: "--", size: 22)
                        }
                        Spacer()
                        Readout(caption: "ETA", value: "--:--", size: 22)
                    }
                }
                .padding(16)
                .foregroundStyle(theme.ink)
                .cvPanel(fill: theme.surface200, edge: theme.signal)
            }
            .buttonStyle(.plain)
        } else {
            VStack(alignment: .leading, spacing: 12) {
                SectionLabel(text: "No convoy running")
                Text("Start one and share the code, or join a friend's.")
                    .font(CvFont.text(14))
                    .foregroundStyle(theme.inkMuted)
                CvButton(title: "Start a convoy") { showCreate = true }
            }
            .padding(16)
            .cvPanel(fill: theme.surface200, edge: theme.lineStrong)
        }
    }

    private func placeholderRow(day: String, date: String, title: String, meta: String) -> some View {
        HStack(spacing: 12) {
            VStack(spacing: 0) {
                Text(day).font(CvFont.label(10)).foregroundStyle(theme.inkMuted)
                Text(date).font(CvFont.mono(18))
            }
            .frame(width: 48, height: 48)
            .cvPanel(fill: theme.surface300, edge: theme.lineStrong, cut: Cut.s)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(CvFont.text(15, weight: .bold))
                Text(meta).font(CvFont.text(12)).foregroundStyle(theme.inkMuted)
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(theme.inkMuted)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 64)
        .cvPanel(fill: theme.surface200, edge: theme.line)
    }
}
