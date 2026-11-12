import Foundation
import SceneKit
import UIKit
import Metal

/// How a finish looks to the vertex shader. Port of Ps1Look in android/.../render/Ps1Gl.kt.
struct Ps1Look {
    var spec: Float
    var shine: Float
    var metal: Float
    var pearl: Float
    var specCol: SIMD3<Float>

    static func of(_ finish: Ps1Finish, paint: UInt32) -> Ps1Look {
        let p: SIMD3<Float> = Ps1Color.linear(rgb: paint)
        switch finish {
        case .gloss: return Ps1Look(spec: 0.55, shine: 28, metal: 0, pearl: 0, specCol: SIMD3<Float>(1, 1, 1))
        case .satin: return Ps1Look(spec: 0.22, shine: 8, metal: 0, pearl: 0, specCol: SIMD3<Float>(1, 1, 1))
        case .matte: return Ps1Look(spec: 0, shine: 1, metal: 0, pearl: 0, specCol: SIMD3<Float>(0, 0, 0))
        case .metallic: return Ps1Look(spec: 0.9, shine: 14, metal: 1, pearl: 0,
                                       specCol: SIMD3<Float>(0.45 + 0.55 * p.x, 0.45 + 0.55 * p.y, 0.45 + 0.55 * p.z))
        case .pearl: return Ps1Look(spec: 0.5, shine: 18, metal: 0, pearl: 0.75, specCol: SIMD3<Float>(0.9, 0.95, 1))
        }
    }
}

/// The uniforms the Metal shader reads, written once per frame. The layout is `Ps1Params` in Ps1.metal: five float4.
final class Ps1Params {
    private var values: [Float] = [
        240, 240, 0, 0,
        0.55, 28, 0, 0,
        1, 1, 1, 0,
        5, 0.8, 0, 0,
        0.5, 0.9, 0.7, 0
    ]

    func setRes(_ w: Float, _ h: Float) {
        values[0] = max(1, w)
        values[1] = max(1, h)
    }

    func setLook(_ l: Ps1Look) {
        values[4] = l.spec
        values[5] = l.shine
        values[6] = l.metal
        values[7] = l.pearl
        values[8] = l.specCol.x
        values[9] = l.specCol.y
        values[10] = l.specCol.z
    }

    func setWheel(_ w: Ps1Wheel) {
        values[12] = Float(w.spokes)
        values[13] = w.lip
        values[14] = w.solid ? 1 : 0
    }

    func snapshot() -> [Float] {
        return values
    }
}

/// The scene behind both the live view and the thumbnails: a camera, the ground, and the vehicle node that turns.
/// Rendered with the PS1 Metal program; if that fails to build or run, it falls back to plain lit materials so the view
/// is never blank.
final class Ps1Scene: NSObject, SCNProgramDelegate {
    static let fovDeg: CGFloat = 30
    static let elevation: Float = 0.3

    let scene: SCNScene = SCNScene()
    let cameraNode: SCNNode = SCNNode()
    let vehicleNode: SCNNode = SCNNode()
    let groundNode: SCNNode = SCNNode()
    let params: Ps1Params = Ps1Params()

    private(set) var usingFallback: Bool = false
    /// Set (from any thread) the moment SceneKit reports the program failed.
    private(set) var programFailed: Bool = false
    private(set) var build: VehicleBuild?

    private var program: SCNProgram?
    private var builtKey: String?
    private var mesh: Ps1Mesh?
    private var aspect: Float = 1
    private var zoom: Float = 1

    /// True when the app's default Metal library has both PS1 functions (the .metal file made it into the build).
    static let shaderAvailable: Bool = {
        guard let device = MTLCreateSystemDefaultDevice(), let lib = device.makeDefaultLibrary() else { return false }
        return lib.makeFunction(name: "ps1_vertex") != nil && lib.makeFunction(name: "ps1_fragment") != nil
    }()

    override init() {
        super.init()
        scene.background.contents = UIColor.clear
        let camera: SCNCamera = SCNCamera()
        camera.fieldOfView = Ps1Scene.fovDeg
        camera.projectionDirection = .vertical
        camera.zNear = 1
        camera.zFar = 60
        cameraNode.camera = camera
        scene.rootNode.addChildNode(groundNode)
        scene.rootNode.addChildNode(vehicleNode)
        scene.rootNode.addChildNode(cameraNode)

        // Only the fallback materials use these; the PS1 program lights per vertex itself.
        let ambient: SCNNode = SCNNode()
        let ambientLight: SCNLight = SCNLight()
        ambientLight.type = .ambient
        ambientLight.intensity = 500
        ambient.light = ambientLight
        scene.rootNode.addChildNode(ambient)
        let sun: SCNNode = SCNNode()
        let sunLight: SCNLight = SCNLight()
        sunLight.type = .directional
        sunLight.intensity = 900
        sun.light = sunLight
        sun.eulerAngles = SCNVector3(-0.9, 0.5, 0)
        scene.rootNode.addChildNode(sun)

        if Ps1Scene.shaderAvailable {
            program = makeProgram()
        } else {
            usingFallback = true
        }
        frame(aspect: 1, zoom: 1)
    }

    private func makeProgram() -> SCNProgram {
        let p: SCNProgram = SCNProgram()
        p.vertexFunctionName = "ps1_vertex"
        p.fragmentFunctionName = "ps1_fragment"
        p.delegate = self
        let prm: Ps1Params = params
        p.handleBinding(ofBufferNamed: "params", frequency: .perFrame) { (stream: SCNBufferStream, node: SCNNode, shadable: SCNShadable, renderer: SCNRenderer) in
            let values: [Float] = prm.snapshot()
            values.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                if let base = raw.baseAddress {
                    stream.writeBytes(base, count: raw.count)
                }
            }
        }
        return p
    }

    // MARK: SCNProgramDelegate

    func program(_ program: SCNProgram, handleError error: Error) {
        programFailed = true
        DispatchQueue.main.async { [weak self] in
            self?.switchToFallback()
        }
    }

    /// Drops the PS1 program for plain materials. Safe to call more than once.
    func switchToFallback() {
        if usingFallback { return }
        usingFallback = true
        program = nil
        if let m = mesh { applyGeometry(m) }
    }

    // MARK: Build and camera

    /// Builds and uploads the model for `b` unless it is already the current one.
    func setBuild(_ b: VehicleBuild) {
        params.setLook(Ps1Look.of(b.finish, paint: b.paint))
        params.setWheel(b.wheel)
        let key: String = b.key()
        if key == builtKey { return }
        builtKey = key
        let kindChanged: Bool = build?.kind != b.kind || build?.body != b.body || build?.shapeId != b.shapeId
        build = b
        let m: Ps1Mesh = Ps1Models.build(b)
        mesh = m
        applyGeometry(m)
        if kindChanged { frame(aspect: aspect, zoom: zoom) }
    }

    /// Straight down with the nose (+x) at the top of the image, orthographic so it reads as a plan view.
    func frameTopDown(aspect: Float) {
        let f: Ps1Frame = Ps1Models.frame(build)
        cameraNode.camera?.usesOrthographicProjection = true
        cameraNode.camera?.orthographicScale = Double(f.fitRadius * 1.02)
        cameraNode.position = SCNVector3(0, 30, 0)
        cameraNode.look(at: SCNVector3(0, 0, 0), up: SCNVector3(1, 0, 0), localFront: SCNVector3(0, 0, -1))
    }

    /// The map-marker angle: an orthographic camera at `elevationDeg` above the horizon, looking at the vehicle's middle.
    /// The vehicle node's own yaw is the heading relative to the camera (see `Ps1Thumbs.angle`).
    func frameAngle(elevationDeg: Float) {
        let f: Ps1Frame = Ps1Models.frame(build)
        cameraNode.camera?.usesOrthographicProjection = true
        cameraNode.camera?.orthographicScale = Double(f.fitRadius)
        let e: Float = elevationDeg * Float.pi / 180
        cameraNode.position = SCNVector3(0, 30 * sinf(e), 30 * cosf(e))
        cameraNode.look(at: SCNVector3(0, 0, 0), up: SCNVector3(0, 1, 0), localFront: SCNVector3(0, 0, -1))
    }

    func setYaw(_ degrees: Float) {
        vehicleNode.eulerAngles.y = degrees * Float.pi / 180
    }

    func setGroundVisible(_ on: Bool) {
        groundNode.isHidden = !on
    }

    /// Camera for a target of the given aspect (width over height), as in Ps1Camera on Android: 30 degree vertical
    /// field of view, backed off to hold the vehicle and further for tall narrow views.
    func frame(aspect: Float, zoom: Float) {
        self.aspect = aspect
        self.zoom = zoom
        cameraNode.camera?.usesOrthographicProjection = false
        let f: Ps1Frame = Ps1Models.frame(build)
        let dist: Float = 12.2 * f.fitRadius / 3.6 * max(1, 1.43 / max(aspect, 0.1)) / max(zoom, 0.1)
        let eyeY: Float = sinf(Ps1Scene.elevation) * dist + f.lookY + 0.05
        let eyeZ: Float = cosf(Ps1Scene.elevation) * dist
        cameraNode.position = SCNVector3(0, eyeY, eyeZ)
        cameraNode.look(at: SCNVector3(0, f.lookY, 0))
    }

    // MARK: Geometry

    private func material() -> SCNMaterial {
        let m: SCNMaterial = SCNMaterial()
        m.isDoubleSided = true
        if let p = program, !usingFallback {
            m.program = p
        } else {
            m.lightingModel = .lambert
            m.diffuse.contents = UIColor.white
        }
        return m
    }

    private func applyGeometry(_ mesh: Ps1Mesh) {
        let verts: [Float] = usingFallback ? Ps1Scene.fallbackVertices(mesh.vertices) : mesh.vertices
        let n: Int = mesh.vertexCount
        let data: Data = verts.withUnsafeBufferPointer { (b: UnsafeBufferPointer<Float>) -> Data in return Data(buffer: b) }
        let stride: Int = Ps1Mesh.stride * 4
        let position: SCNGeometrySource = SCNGeometrySource(data: data, semantic: .vertex, vectorCount: n, usesFloatComponents: true,
                                                            componentsPerVector: 3, bytesPerComponent: 4, dataOffset: 0, dataStride: stride)
        let normal: SCNGeometrySource = SCNGeometrySource(data: data, semantic: .normal, vectorCount: n, usesFloatComponents: true,
                                                          componentsPerVector: 3, bytesPerComponent: 4, dataOffset: 12, dataStride: stride)
        let uv: SCNGeometrySource = SCNGeometrySource(data: data, semantic: .texcoord, vectorCount: n, usesFloatComponents: true,
                                                      componentsPerVector: 2, bytesPerComponent: 4, dataOffset: 24, dataStride: stride)
        let color: SCNGeometrySource = SCNGeometrySource(data: data, semantic: .color, vectorCount: n, usesFloatComponents: true,
                                                         componentsPerVector: 4, bytesPerComponent: 4, dataOffset: 32, dataStride: stride)
        let sources: [SCNGeometrySource] = [position, normal, uv, color]
        let split: Int = mesh.groundIndexCount
        groundNode.geometry = geometry(sources, Array(mesh.indices[0..<split]))
        vehicleNode.geometry = geometry(sources, Array(mesh.indices[split..<mesh.indices.count]))
    }

    private func geometry(_ sources: [SCNGeometrySource], _ indices: [UInt32]) -> SCNGeometry? {
        if indices.count < 3 { return nil }
        let data: Data = indices.withUnsafeBufferPointer { (b: UnsafeBufferPointer<UInt32>) -> Data in return Data(buffer: b) }
        let element: SCNGeometryElement = SCNGeometryElement(data: data, primitiveType: .triangles,
                                                             primitiveCount: indices.count / 3, bytesPerIndex: 4)
        let g: SCNGeometry = SCNGeometry(sources: sources, elements: [element])
        g.materials = [material()]
        return g
    }

    /// The fallback has no shader to decode the tint and the texture code, so bake a plain sRGB colour per vertex.
    static func fallbackVertices(_ v: [Float]) -> [Float] {
        var out: [Float] = v
        let stride: Int = Ps1Mesh.stride
        var i: Int = 0
        while i + stride <= out.count {
            let tex: Int = Int(out[i + 11] + 0.5) >> 2
            var r: Float = powf(max(0, out[i + 8]), 1.0 / 2.2)
            var g: Float = powf(max(0, out[i + 9]), 1.0 / 2.2)
            var b: Float = powf(max(0, out[i + 10]), 1.0 / 2.2)
            if tex == 3 {
                r = 0.086
                g = 0.149
                b = 0.227
            } else if tex == 2 {
                r *= 0.7
                g *= 0.7
                b *= 0.7
            }
            out[i + 8] = r
            out[i + 9] = g
            out[i + 10] = b
            out[i + 11] = 1
            i += stride
        }
        return out
    }
}
