import Foundation
import CoreMotion
import Observation

/// Lean angle for Ride mode, from CoreMotion gravity. Assumes the phone is mounted upright (portrait) on the bike:
/// the angle is the roll of the phone about its long axis, low-passed so bumps do not jitter the readout.
/// Positive = leaning right. Also keeps the largest angle each way for the recap.
@Observable
@MainActor
final class LeanMonitor {
    /// Smoothed lean in degrees, nil until the first sample or when CoreMotion is unavailable.
    var degrees: Double?
    var maxLeft: Double = 0
    var maxRight: Double = 0

    @ObservationIgnored private let motion: CMMotionManager = CMMotionManager()
    @ObservationIgnored private var smoothed: Double?

    /// Pure maths, split out for tests: roll from the gravity vector of an upright phone.
    nonisolated static func rollDegrees(gravityX: Double, gravityY: Double) -> Double {
        return atan2(gravityX, -gravityY) * 180.0 / Double.pi
    }

    /// Exponential smoothing; `alpha` 1 = no smoothing.
    nonisolated static func smooth(previous: Double?, sample: Double, alpha: Double = 0.2) -> Double {
        guard let p = previous else { return sample }
        return p + alpha * (sample - p)
    }

    func start() {
        guard motion.isDeviceMotionAvailable, !motion.isDeviceMotionActive else { return }
        maxLeft = 0
        maxRight = 0
        smoothed = nil
        motion.deviceMotionUpdateInterval = 1.0 / 30.0
        motion.startDeviceMotionUpdates(to: OperationQueue.main) { [weak self] (data: CMDeviceMotion?, _: Error?) in
            guard let d = data else { return }
            let roll: Double = LeanMonitor.rollDegrees(gravityX: d.gravity.x, gravityY: d.gravity.y)
            MainActor.assumeIsolated { self?.ingest(roll) }
        }
    }

    func stop() {
        motion.stopDeviceMotionUpdates()
        degrees = nil
        smoothed = nil
    }

    private func ingest(_ roll: Double) {
        let s: Double = LeanMonitor.smooth(previous: smoothed, sample: roll)
        smoothed = s
        degrees = s
        if s > maxRight { maxRight = s }
        if -s > maxLeft { maxLeft = -s }
    }
}
