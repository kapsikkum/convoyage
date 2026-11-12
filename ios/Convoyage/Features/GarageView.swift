import SwiftUI

extension Color {
    /// True when a `#RRGGBB` colour is light enough to need dark text on top of it.
    static func isLightHex(_ hexString: String) -> Bool {
        var s: String = hexString.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return false }
        let r: Double = Double((v >> 16) & 0xFF)
        let g: Double = Double((v >> 8) & 0xFF)
        let b: Double = Double(v & 0xFF)
        return (0.299 * r + 0.587 * g + 0.114 * b) > 150
    }
}

/// A vehicle on a card: its PS1 render (a cached thumbnail) with the name over the bottom.
struct VehicleCard: View {
    let vehicle: VehicleDto
    var height: CGFloat = 180
    @Environment(\.theme) private var theme

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            ChamferShape(cut: Cut.l).fill(theme.void)
            Ps1Thumb(build: VehicleBuild(vehicle: vehicle), size: Ps1ThumbSize.card)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            VehicleCaption(vehicle: vehicle)
        }
        .frame(height: height)
        .clipShape(ChamferShape(cut: Cut.l))
        .overlay(ChamferShape(cut: Cut.l).stroke(theme.lineStrong, lineWidth: 1))
    }
}

/// The garage detail hero: the live PS1 renderer, turning on its own and under a finger.
struct VehicleHero: View {
    let vehicle: VehicleDto
    var height: CGFloat = 260
    @Environment(\.theme) private var theme

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            ChamferShape(cut: Cut.l).fill(theme.void)
            Ps1LiveView(build: VehicleBuild(vehicle: vehicle))
            VehicleCaption(vehicle: vehicle).allowsHitTesting(false)
        }
        .frame(height: height)
        .clipShape(ChamferShape(cut: Cut.l))
        .overlay(ChamferShape(cut: Cut.l).stroke(theme.lineStrong, lineWidth: 1))
    }
}

/// Kind label at the top, name and details at the bottom, over a fade so they read on the render.
struct VehicleCaption: View {
    let vehicle: VehicleDto
    @Environment(\.theme) private var theme

    private var detailLine: String {
        var parts: [String] = [vehicle.subtitle]
        if let f = vehicle.build?.finish { parts.append(f.capitalized) }
        if let w = vehicle.build?.wheel { parts.append(GarageCatalog.name(of: Ps1Wheels.of(w).id, in: GarageCatalog.wheels)) }
        return parts.joined(separator: " / ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(GarageCatalog.name(of: vehicle.kind, in: GarageCatalog.kinds).uppercased())
                .font(CvFont.label(10))
                .tracking(1.4)
                .foregroundStyle(theme.signal)
            Spacer()
            Text(vehicle.title).font(CvFont.display(22))
            Text(detailLine).font(CvFont.text(12, weight: .semibold)).foregroundStyle(theme.inkMuted)
        }
        .foregroundStyle(theme.ink)
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(
            LinearGradient(colors: [Color.clear, theme.void.opacity(0.85)], startPoint: .center, endPoint: .bottom)
        )
    }
}

/// A vehicle being added (`vehicle` nil) or edited.
struct VehicleEdit: Identifiable {
    let id: String
    let vehicle: VehicleDto?
}

struct GarageView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @State private var editing: VehicleEdit?
    @State private var selectedId: String?
    @State private var errorText: String?
    @State private var confirmDelete: Bool = false
    @State private var confirmSold: Bool = false

    private var selected: VehicleDto? {
        let list: [VehicleDto] = app.garage.vehicles
        if let id = selectedId, let v = list.first(where: { (x: VehicleDto) -> Bool in return x.id == id }) { return v }
        return app.garage.primary ?? list.first
    }

    var body: some View {
        let garage: GarageStore = app.garage
        PageScaffold(eyebrow: "Your garage", title: "Garage") {
            if let e = errorText ?? garage.error {
                Banner(title: e, kind: .pulse, icon: "exclamationmark.triangle", actionTitle: "OK", action: { errorText = nil })
            }
            if app.authKind != .account {
                Banner(title: "Sign in with your email to keep a garage",
                       subtitle: "Dev sign-in has no account behind it, so the server cannot store vehicles.",
                       kind: .hazard, icon: "lock.fill")
            }

            if let v = selected {
                VehicleHero(vehicle: v, height: 260)
                actions(v)
            } else if garage.loaded {
                VStack(spacing: 6) {
                    Image(systemName: "car")
                        .font(.system(size: 34))
                        .foregroundStyle(theme.signal)
                    Text("NO VEHICLES YET")
                        .font(CvFont.label(11))
                        .tracking(1.6)
                        .foregroundStyle(theme.inkMuted)
                }
                .frame(maxWidth: .infinity, minHeight: 160)
                .cvPanel(fill: theme.void, edge: theme.lineStrong, cut: Cut.l)
            } else if garage.loading {
                HStack { Spacer(); ProgressView(); Spacer() }.frame(minHeight: 120)
            }

            VStack(spacing: 10) {
                ForEach(garage.vehicles) { (v: VehicleDto) in
                    Button {
                        selectedId = v.id
                    } label: {
                        row(v)
                    }
                    .buttonStyle(.plain)
                }
            }

            CvButton(title: "Add a vehicle", icon: "plus") {
                editing = VehicleEdit(id: "new", vehicle: nil)
            }
        }
        .task {
            await garage.loadCatalogue()
            await garage.load()
        }
        .refreshable {
            await garage.load()
        }
        .sheet(item: $editing) { (e: VehicleEdit) in
            VehicleFormView(vehicle: e.vehicle)
        }
        .confirmationDialog("Delete this vehicle?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                guard let v = selected else { return }
                Task {
                    errorText = await garage.delete(id: v.id)
                    selectedId = nil
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Mark this vehicle as sold?", isPresented: $confirmSold, titleVisibility: .visible) {
            Button("Mark sold") {
                guard let v = selected else { return }
                Task { errorText = await garage.markSold(id: v.id) }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func row(_ v: VehicleDto) -> some View {
        let isSel: Bool = (v.id == selected?.id)
        return HStack(spacing: 12) {
            Ps1Thumb(build: VehicleBuild(vehicle: v), size: Ps1ThumbSize.badge)
                .frame(width: 44, height: 44)
                .background(Circle().fill(theme.surface300))
                .clipShape(Circle())
                .overlay(Circle().stroke(theme.lineStrong, lineWidth: 1))
            VStack(alignment: .leading, spacing: 2) {
                Text(v.title).font(CvFont.display(18))
                Text(v.subtitle).font(CvFont.text(12)).foregroundStyle(theme.inkMuted)
            }
            Spacer()
            if v.soldAt != nil {
                badge("SOLD", color: theme.inkMuted)
            } else if v.isPrimary ?? false {
                badge("PRIMARY", color: theme.signal)
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 64)
        .foregroundStyle(theme.ink)
        .cvPanel(fill: theme.surface200, edge: isSel ? theme.signal : theme.line)
    }

    private func badge(_ text: String, color: Color) -> some View {
        Text(text)
            .font(CvFont.label(10))
            .tracking(1.2)
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .frame(minHeight: 24)
            .cvPanel(fill: Color.clear, edge: color, cut: 4)
    }

    @ViewBuilder
    private func actions(_ v: VehicleDto) -> some View {
        let sold: Bool = v.soldAt != nil
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                CvButton(title: "Edit", icon: "pencil", kind: .ghost) {
                    editing = VehicleEdit(id: v.id, vehicle: v)
                }
                if !sold && !(v.isPrimary ?? false) {
                    CvButton(title: "Primary", icon: "star", kind: .ghost) {
                        Task { errorText = await app.garage.setPrimary(id: v.id) }
                    }
                }
            }
            HStack(spacing: 10) {
                if !sold {
                    CvButton(title: "Mark sold", icon: "tag", kind: .ghost) { confirmSold = true }
                }
                CvButton(title: "Delete", icon: "trash", kind: .ghost) { confirmDelete = true }
            }
        }
    }
}

// MARK: - Form

/// A grid of single-select chips from a catalogue.
struct ChoiceGrid: View {
    let items: [CatalogItem]
    @Binding var selection: String
    @Environment(\.theme) private var theme

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 8) {
            ForEach(items) { (item: CatalogItem) in
                let on: Bool = (item.id == selection)
                Button {
                    selection = item.id
                } label: {
                    Text(item.name.uppercased())
                        .font(CvFont.label(11))
                        .tracking(1.0)
                        .foregroundStyle(on ? theme.signalInk : theme.ink)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .cvPanel(fill: on ? theme.signal : theme.surface200, edge: on ? theme.signal : theme.lineStrong, cut: Cut.s)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

struct VehicleFormView: View {
    @Environment(AppState.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    let vehicle: VehicleDto?
    @State private var draft: VehicleDraft = VehicleDraft()
    @State private var saving: Bool = false
    @State private var errorText: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    Text(vehicle == nil ? "Add a vehicle" : "Edit vehicle").font(CvFont.display(26))
                    Spacer()
                    Button("Cancel") { dismiss() }
                        .font(CvFont.text(15, weight: .bold))
                        .foregroundStyle(theme.inkMuted)
                }

                group("Kind") {
                    ChoiceGrid(items: GarageCatalog.kinds, selection: $draft.kind)
                }
                group("Make") { CvField(placeholder: "Toyota", text: $draft.make, autocap: .words) }
                group("Model") { CvField(placeholder: "GR Yaris", text: $draft.model, autocap: .words) }
                group("Year (optional)") { CvField(placeholder: "2022", text: $draft.yearText, keyboard: .numberPad) }
                group("Nickname (optional)") { CvField(placeholder: "Daily", text: $draft.nickname, autocap: .words) }

                if !variants.isEmpty {
                    group(chosen.map { (m: Ps1Model) -> String in
                        return "Model · " + [m.make, m.model, m.gen].filter { (s: String) -> Bool in return !s.isEmpty }.joined(separator: " ")
                    } ?? "Model · pick one") {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(variants, id: \.id) { (m: Ps1Model) in
                                    ShapeTile(
                                        build: preview(kind: m.body.kind.rawValue, body: m.body.rawValue, shape: m.id),
                                        label: [m.model, m.gen].filter { (s: String) -> Bool in return !s.isEmpty }.joined(separator: " "),
                                        sub: m.yearsLabel,
                                        on: chosen?.id == m.id
                                    ) {
                                        draft.kind = m.body.kind.rawValue
                                        draft.body = m.body.rawValue
                                        draft.shape = m.id
                                    }
                                }
                            }
                        }
                    }
                }

                group("Body · \(bodyNow.label)") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Ps1Body.forKind(kindNow), id: \.rawValue) { (b: Ps1Body) in
                                ShapeTile(
                                    build: preview(kind: draft.kind, body: b.rawValue, shape: VehicleBuild.genericShape),
                                    label: b.label,
                                    sub: nil,
                                    on: chosen == nil && b == bodyNow
                                ) {
                                    draft.body = b.rawValue
                                    draft.shape = VehicleBuild.genericShape
                                }
                            }
                        }
                    }
                }

                group("Paint") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 52), spacing: 12)], spacing: 12) {
                        ForEach(0..<GarageCatalog.paints.count, id: \.self) { (i: Int) in
                            let p = GarageCatalog.paints[i]
                            let on: Bool = (p.hex == draft.paint)
                            Button {
                                draft.paint = p.hex
                            } label: {
                                Circle()
                                    .fill(Color(hexString: p.hex))
                                    .frame(width: 44, height: 44)
                                    .overlay(Circle().stroke(on ? theme.signal : theme.lineStrong, lineWidth: on ? 3 : 1))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(p.name)
                        }
                    }
                    Text(paintName).font(CvFont.text(12)).foregroundStyle(theme.inkMuted)
                }

                group("Finish") { ChoiceGrid(items: GarageCatalog.finishes, selection: $draft.finish) }
                group("Wheels") { ChoiceGrid(items: GarageCatalog.wheels, selection: $draft.wheel) }

                group("Ride height") {
                    HStack {
                        Readout(caption: "Offset from stock", value: "\(draft.rideHeightMm)", unit: "mm", size: 24)
                        Spacer()
                        Stepper("Ride height", value: $draft.rideHeightMm, in: -200...200, step: 5)
                            .labelsHidden()
                    }
                }

                group("Decals") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 8)], spacing: 8) {
                        ForEach(GarageCatalog.decals) { (item: CatalogItem) in
                            let on: Bool = draft.decalIds.contains(item.id)
                            Button {
                                if on { draft.decalIds.remove(item.id) } else { draft.decalIds.insert(item.id) }
                            } label: {
                                Text(item.name.uppercased())
                                    .font(CvFont.label(11))
                                    .tracking(1.0)
                                    .foregroundStyle(on ? theme.signalInk : theme.ink)
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .cvPanel(fill: on ? theme.signal : theme.surface200, edge: on ? theme.signal : theme.lineStrong, cut: Cut.s)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                if vehicle == nil {
                    Toggle(isOn: $draft.makePrimary) {
                        Text("Make this my primary vehicle").font(CvFont.text(14, weight: .semibold))
                    }
                    .tint(theme.signal)
                }

                if let e = errorText {
                    Banner(title: e, kind: .pulse, icon: "exclamationmark.triangle")
                }
                CvButton(title: saving ? "Saving..." : "Save") {
                    save()
                }
                .opacity(draft.isValid && !saving ? 1 : 0.5)
                .disabled(!draft.isValid || saving)
            }
            .padding(20)
        }
        .scrollDismissesKeyboard(.interactively)
        .onChange(of: draft.make) { (_, _) in editedShape() }
        .onChange(of: draft.model) { (_, _) in editedShape() }
        .onChange(of: draft.yearText) { (_, _) in editedShape() }
        .safeAreaInset(edge: .top, spacing: 0) {
            Ps1LiveView(build: VehicleBuild(draft: draft))
                .frame(height: 190)
                .frame(maxWidth: .infinity)
                .background(theme.void)
        }
        .background(theme.surface100.ignoresSafeArea())
        .foregroundStyle(theme.ink)
        .onAppear {
            if let v = vehicle { draft = VehicleDraft(from: v) }
        }
        .task {
            await app.garage.loadCatalogue()
        }
    }

    private var paintName: String {
        for p in GarageCatalog.paints where p.hex == draft.paint {
            return p.name
        }
        return draft.paint
    }

    private var kindNow: Ps1Kind {
        return Ps1Kind.of(draft.kind)
    }

    private var yearNow: Int? {
        return Int(draft.yearText)
    }

    private var chosen: Ps1Model? {
        return draft.chosenModel
    }

    private var bodyNow: Ps1Body {
        return draft.chosenBody
    }

    private var variants: [Ps1Model] {
        return Ps1Catalog.candidates(make: draft.make, model: draft.model, year: yearNow)
    }

    private func preview(kind: String, body: String, shape: String) -> VehicleBuild {
        return VehicleBuild(kind: kind, paint: draft.paint, finish: draft.finish, wheel: draft.wheel,
                            rideHeightMm: draft.rideHeightMm, body: body, shape: shape)
    }

    /// Typing another car drops a model picked by hand, so the match follows the text again.
    private func editedShape() {
        if draft.shape != VehicleBuild.genericShape { draft.shape = nil }
    }

    private func group<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(text: title)
            content()
        }
    }

    private func save() {
        saving = true
        errorText = nil
        Task {
            let err: String? = await app.garage.save(id: vehicle?.id, draft: draft)
            saving = false
            if let e = err {
                errorText = e
            } else {
                dismiss()
            }
        }
    }
}

/// A body or catalogue model to pick: its render on the current paint, a name and an optional second line.
struct ShapeTile: View {
    let build: VehicleBuild
    let label: String
    var sub: String? = nil
    let on: Bool
    let onPick: () -> Void
    @Environment(\.theme) private var theme

    var body: some View {
        Button(action: onPick) {
            VStack(spacing: 3) {
                Ps1Thumb(build: build, size: Ps1ThumbSize.compact)
                    .frame(width: 128, height: 84)
                Text(label.uppercased())
                    .font(CvFont.label(9))
                    .tracking(0.8)
                    .lineLimit(1)
                    .foregroundStyle(on ? theme.signal : theme.ink)
                if let s = sub {
                    Text(s.uppercased())
                        .font(CvFont.text(9, weight: .semibold))
                        .lineLimit(1)
                        .foregroundStyle(theme.inkMuted)
                }
            }
            .frame(width: 136)
            .padding(.vertical, 6)
            .cvPanel(fill: on ? theme.surface300 : theme.surface200, edge: on ? theme.signal : theme.line, cut: Cut.s)
        }
        .buttonStyle(.plain)
    }
}
