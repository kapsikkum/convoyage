import SwiftUI
import SceneKit
import UIKit
import Metal
import QuartzCore

// MARK: - Live view

/// Turns the vehicle on its own and under a finger. Lives apart from the view so the render thread never touches
/// main-actor state: SceneKit calls `renderer(_:updateAtTime:)` on its own thread.
final class Ps1Driver: NSObject, SCNSceneRendererDelegate {
    let ps1: Ps1Scene = Ps1Scene()
    private var yawDeg: Float = -32
    private var lastTime: CFTimeInterval = 0
    private var touching: Bool = false
    private var lastTouch: CFTimeInterval = 0

    func beginTouch() {
        touching = true
    }

    func endTouch() {
        touching = false
        lastTouch = CACurrentMediaTime()
    }

    func addYaw(_ degrees: Float) {
        yawDeg += degrees
    }

    func renderer(_ renderer: SCNSceneRenderer, updateAtTime time: TimeInterval) {
        let now: CFTimeInterval = CACurrentMediaTime()
        // About 22 degrees a second, paused while a finger is down and for a moment after.
        if lastTime != 0 && !touching && now - lastTouch > 1.2 {
            yawDeg += Float(now - lastTime) * 22
        }
        lastTime = now
        ps1.setYaw(yawDeg)
    }
}

/// An SCNView drawn at about 240 rows and shown with nearest-neighbour filtering, so the pixels stay chunky at any
/// screen size. The low resolution comes from a fractional `contentScaleFactor`.
final class Ps1SCNView: SCNView, UIGestureRecognizerDelegate {
    static let targetRows: CGFloat = 240

    private let driver: Ps1Driver = Ps1Driver()

    init() {
        super.init(frame: CGRect.zero, options: nil)
        scene = driver.ps1.scene
        pointOfView = driver.ps1.cameraNode
        delegate = driver
        backgroundColor = UIColor.clear
        isOpaque = false
        antialiasingMode = .none
        preferredFramesPerSecond = 30
        isPlaying = true
        allowsCameraControl = false
        autoenablesDefaultLighting = false
        layer.magnificationFilter = CALayerContentsFilter.nearest
        layer.minificationFilter = CALayerContentsFilter.nearest
        let pan: UIPanGestureRecognizer = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
        pan.delegate = self
        addGestureRecognizer(pan)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    func setBuild(_ b: VehicleBuild) {
        driver.ps1.setBuild(b)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        isPlaying = (window != nil)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w: CGFloat = bounds.width
        let h: CGFloat = bounds.height
        if w < 2 || h < 2 { return }
        let factor: CGFloat = min(UIScreen.main.scale, max(0.1, Ps1SCNView.targetRows / h))
        if abs(contentScaleFactor - factor) > 0.001 {
            contentScaleFactor = factor
        }
        driver.ps1.params.setRes(Float((w * factor).rounded()), Float((h * factor).rounded()))
        driver.ps1.frame(aspect: Float(w / h), zoom: 1)
    }

    @objc private func handlePan(_ g: UIPanGestureRecognizer) {
        switch g.state {
        case .began:
            driver.beginTouch()
        case .changed:
            let dx: CGFloat = g.translation(in: self).x
            g.setTranslation(CGPoint.zero, in: self)
            driver.addYaw(Float(dx) * 0.6)
        case .ended, .cancelled, .failed:
            driver.endTouch()
        default:
            break
        }
    }

    /// Only a mostly horizontal drag turns the car, so a vertical scroll through it still scrolls the page.
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if let pan = gestureRecognizer as? UIPanGestureRecognizer {
            let v: CGPoint = pan.velocity(in: self)
            return abs(v.x) > abs(v.y)
        }
        return true
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        return false
    }
}

/// The live PS1 view for the garage detail and the editor. The build can change on every update and the model follows.
struct Ps1LiveView: UIViewRepresentable {
    let build: VehicleBuild

    func makeUIView(context: Context) -> Ps1SCNView {
        let v: Ps1SCNView = Ps1SCNView()
        v.setBuild(build)
        return v
    }

    func updateUIView(_ uiView: Ps1SCNView, context: Context) {
        uiView.setBuild(build)
    }
}

// MARK: - Thumbnails

/// The two thumbnail shapes. Sizes are the low-res render, drawn scaled up with nearest-neighbour filtering.
enum Ps1ThumbSize {
    /// Avatar badge: square, no ground, tight on the vehicle.
    case badge
    /// Cards and lists: the same framing as the live view, with the grid floor.
    case card
    /// Map marker: a plan view from straight above, nose at the top.
    case top

    var name: String {
        switch self {
        case .badge: return "Badge"
        case .card: return "Card"
        case .top: return "Top"
        }
    }

    var width: Int {
        switch self {
        case .badge: return 72
        case .card: return 192
        case .top: return 40
        }
    }
    var height: Int {
        switch self {
        case .badge: return 72
        case .card: return 128
        case .top: return 96
        }
    }
    var ground: Bool { return self == .card }
    var zoom: Float { return self == .badge ? 1.5 : 1 }
    var yawDeg: Float { return -32 }
}

/// Static PS1 renders for everywhere except the live view. Each build is drawn once by an SCNRenderer into a small
/// image, cached in memory and on disk under `VehicleBuild.key`, so a vehicle is only drawn again after its build (or
/// the renderer version) changes.
final class Ps1Thumbs {
    static let shared: Ps1Thumbs = Ps1Thumbs()

    private static let memory: NSCache<NSString, UIImage> = {
        let c: NSCache<NSString, UIImage> = NSCache<NSString, UIImage>()
        c.countLimit = 300
        return c
    }()

    private var inFlight: [String: Task<UIImage?, Never>] = [:]
    private var scene: Ps1Scene?
    private var renderer: SCNRenderer?
    private var unavailable: Bool = false

    /// The thumbnail if it is already in memory. Cheap enough to call while building a view.
    static func cached(_ b: VehicleBuild, _ size: Ps1ThumbSize) -> UIImage? {
        return memory.object(forKey: b.key(size.name) as NSString)
    }

    /// The thumbnail from memory, then disk, then the GPU. Nil if the device cannot render it.
    @MainActor
    func image(_ b: VehicleBuild, _ size: Ps1ThumbSize) async -> UIImage? {
        let key: String = b.key(size.name)
        if let hit = Ps1Thumbs.memory.object(forKey: key as NSString) { return hit }
        if let running = inFlight[key] { return await running.value }
        let task: Task<UIImage?, Never> = Task { @MainActor () -> UIImage? in
            var img: UIImage? = await Ps1Thumbs.readDisk(key)
            if img == nil {
                img = self.render(b, size)
                if let made = img { Ps1Thumbs.writeDisk(key, made) }
            }
            if let done = img { Ps1Thumbs.memory.setObject(done, forKey: key as NSString) }
            return img
        }
        inFlight[key] = task
        let result: UIImage? = await task.value
        inFlight[key] = nil
        return result
    }

    // MARK: Disk

    private static var directory: URL? {
        guard let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        return base.appendingPathComponent("ps1", isDirectory: true)
    }

    private static func readDisk(_ key: String) async -> UIImage? {
        guard let dir = directory else { return nil }
        let url: URL = dir.appendingPathComponent(key + ".png")
        return await Task.detached(priority: .utility) { () -> UIImage? in
            return UIImage(contentsOfFile: url.path)
        }.value
    }

    private static func writeDisk(_ key: String, _ image: UIImage) {
        guard let dir = directory, let data = image.pngData() else { return }
        let url: URL = dir.appendingPathComponent(key + ".png")
        Task.detached(priority: .utility) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? data.write(to: url, options: .atomic)
        }
    }

    // MARK: GPU

    @MainActor
    private func render(_ b: VehicleBuild, _ size: Ps1ThumbSize) -> UIImage? {
        if unavailable { return nil }
        if scene == nil || renderer == nil {
            guard let device = MTLCreateSystemDefaultDevice() else {
                unavailable = true
                return nil
            }
            let s: Ps1Scene = Ps1Scene()
            let r: SCNRenderer = SCNRenderer(device: device, options: nil)
            r.scene = s.scene
            r.pointOfView = s.cameraNode
            scene = s
            renderer = r
        }
        guard let s = scene, let r = renderer else { return nil }
        let w: CGFloat = CGFloat(size.width)
        let h: CGFloat = CGFloat(size.height)
        s.params.setRes(Float(size.width), Float(size.height))
        s.setBuild(b)
        s.setGroundVisible(size.ground)
        if size == .top {
            s.setYaw(0)
            s.frameTopDown(aspect: Float(w / h))
        } else {
            s.setYaw(size.yawDeg)
            s.frame(aspect: Float(w / h), zoom: size.zoom)
        }
        r.pointOfView = s.cameraNode
        var img: UIImage = r.snapshot(atTime: 0, with: CGSize(width: w, height: h), antialiasingMode: SCNAntialiasingMode.none)
        if s.programFailed && !s.usingFallback {
            // The PS1 program was refused while drawing that: show the plain version instead of a blank.
            s.switchToFallback()
            img = r.snapshot(atTime: 0, with: CGSize(width: w, height: h), antialiasingMode: SCNAntialiasingMode.none)
        }
        return img
    }
}

/// A static PS1 render, drawn with nearest-neighbour filtering so the pixels stay square.
struct Ps1Thumb: View {
    let build: VehicleBuild
    var size: Ps1ThumbSize = Ps1ThumbSize.badge
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let img = image ?? Ps1Thumbs.cached(build, size) {
                Image(uiImage: img)
                    .resizable()
                    .interpolation(.none)
                    .antialiased(false)
                    .scaledToFit()
            }
        }
        .task(id: build.key(size.name)) {
            await load()
        }
    }

    private func load() async {
        if let hit = Ps1Thumbs.cached(build, size) {
            image = hit
            return
        }
        image = await Ps1Thumbs.shared.image(build, size)
    }
}
