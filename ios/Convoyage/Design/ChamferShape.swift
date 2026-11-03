import SwiftUI

/// Cut sizes from the design system.
enum Cut {
    static let s: CGFloat = 6
    static let m: CGFloat = 12
    static let l: CGFloat = 20
}

/// A rectangle with the top-left and bottom-right corners cut diagonally.
struct ChamferShape: Shape {
    var cut: CGFloat = Cut.m

    var animatableData: CGFloat {
        get { return cut }
        set { cut = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let c: CGFloat = min(cut, min(rect.width, rect.height) / 2)
        var p = Path()
        p.move(to: CGPoint(x: rect.minX + c, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - c))
        p.addLine(to: CGPoint(x: rect.maxX - c, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + c))
        p.closeSubpath()
        return p
    }
}

extension View {
    /// Chamfered panel: fill, 1pt edge, clipped to the shape.
    func cvPanel(fill: Color, edge: Color, cut: CGFloat = Cut.m, lineWidth: CGFloat = 1) -> some View {
        self
            .background(ChamferShape(cut: cut).fill(fill))
            .overlay(ChamferShape(cut: cut).stroke(edge, lineWidth: lineWidth))
            .clipShape(ChamferShape(cut: cut))
            .contentShape(ChamferShape(cut: cut))
    }
}
