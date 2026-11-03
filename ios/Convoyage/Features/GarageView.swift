import SwiftUI

struct GarageView: View {
    @Environment(\.theme) private var theme

    private struct Car: Identifiable {
        let id: String
        let name: String
        let detail: String
    }

    // TODO(garage): load the user's vehicles from the server.
    private let cars: [Car] = [
        Car(id: "1", name: "GR Yaris", detail: "Pearl white / 18in bronze"),
        Car(id: "2", name: "MT-09", detail: "Matte grey / Quickshifter")
    ]

    var body: some View {
        PageScaffold(eyebrow: "Your garage", title: "Garage") {
            // PLACEHOLDER: the PS1-style 3D renderer will replace this box.
            VStack(spacing: 6) {
                Image(systemName: "cube.transparent")
                    .font(.system(size: 36))
                    .foregroundStyle(theme.signal)
                Text("PS1-STYLE RENDERER GOES HERE")
                    .font(CvFont.label(11))
                    .tracking(1.6)
                    .foregroundStyle(theme.inkMuted)
            }
            .frame(maxWidth: .infinity, minHeight: 200)
            .cvPanel(fill: theme.void, edge: theme.lineStrong, cut: Cut.l)

            VStack(spacing: 10) {
                ForEach(cars) { (car: Car) in
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(car.name).font(CvFont.display(18))
                            Text(car.detail).font(CvFont.text(12)).foregroundStyle(theme.inkMuted)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(theme.inkMuted)
                    }
                    .padding(.horizontal, 14)
                    .frame(minHeight: 64)
                    .cvPanel(fill: theme.surface200, edge: theme.line)
                }
            }
        }
    }
}
