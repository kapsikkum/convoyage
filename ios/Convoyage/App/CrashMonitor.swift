import Foundation
import CoreMotion
import CoreLocation
import UIKit
import Observation
import UserNotifications

/// Opt-in crash detection for Ride mode. Feeds CoreMotion user acceleration and GPS speed to `CrashDetector`; on a
/// trigger it runs a 30 second countdown with a big "I'm OK" button. If the countdown runs out the convoy gets an SOS
/// status and a chat message with the location. It NEVER calls emergency services.
///
/// When the app is not in the foreground at trigger time a time-sensitive local notification tells the rider the
/// countdown has started, so they can open the app and tap "I'm OK".
@Observable
@MainActor
final class CrashMonitor {
    enum Phase: Equatable {
        case idle
        case countdown(secondsLeft: Int)
        /// The countdown ran out. `alerted` = the convoy was told.
        case expired(alerted: Bool)
    }

    static let countdownSeconds: Int = 30
    private static let enabledKey: String = "crashDetectionEnabled"

    var phase: Phase = .idle
    /// The person's opt-in (Ride mode).
    var enabled: Bool = UserDefaults.standard.bool(forKey: "crashDetectionEnabled")
    /// CoreMotion is running.
    var monitoring: Bool = false

    /// Called when the countdown runs out. Returns true when a convoy was told.
    @ObservationIgnored var onExpire: (() -> Bool)?

    @ObservationIgnored private var detector: CrashDetector = CrashDetector()
    @ObservationIgnored private let motion: CMMotionManager = CMMotionManager()
    @ObservationIgnored private let location: LocationService
    @ObservationIgnored private var poll: Task<Void, Never>?
    @ObservationIgnored private var countdown: Task<Void, Never>?

    init(location: LocationService) {
        self.location = location
    }

    func setEnabled(_ on: Bool) {
        enabled = on
        UserDefaults.standard.set(on, forKey: Self.enabledKey)
        if on {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .timeSensitive]) { (_: Bool, _: Error?) in }
        } else {
            stopMonitoring()
        }
    }

    /// Runs the sensors only while they are wanted (Ride mode open or a ride recording) and the setting is on.
    func update(shouldRun: Bool) {
        if enabled && shouldRun {
            startMonitoring()
        } else {
            stopMonitoring()
        }
    }

    private func startMonitoring() {
        if monitoring { return }
        guard motion.isDeviceMotionAvailable else { return }
        monitoring = true
        detector.reset()
        motion.deviceMotionUpdateInterval = 1.0 / 50.0
        motion.startDeviceMotionUpdates(to: OperationQueue.main) { [weak self] (data: CMDeviceMotion?, _: Error?) in
            guard let d = data else { return }
            let a: CMAcceleration = d.userAcceleration
            let g: Double = (a.x * a.x + a.y * a.y + a.z * a.z).squareRoot()
            self?.ingestAcceleration(g)
        }
        poll = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                self?.pollSpeed()
            }
        }
    }

    private func stopMonitoring() {
        if monitoring {
            motion.stopDeviceMotionUpdates()
        }
        monitoring = false
        poll?.cancel()
        poll = nil
        detector.reset()
    }

    private func now() -> Double {
        return ProcessInfo.processInfo.systemUptime
    }

    private func ingestAcceleration(_ g: Double) {
        detector.addAcceleration(g: g, at: now())
    }

    private func pollSpeed() {
        var speed: Double? = nil
        if let loc = location.latest, abs(loc.timestamp.timeIntervalSinceNow) < 5, loc.speed >= 0 {
            speed = loc.speed
        }
        if detector.addSpeed(speed, at: now()) {
            begin()
        }
    }

    // MARK: Countdown

    /// Starts the 30 second countdown (also used by the DEBUG menu).
    func begin() {
        if case .countdown = phase { return }
        phase = .countdown(secondsLeft: Self.countdownSeconds)
        UIApplication.shared.isIdleTimerDisabled = true
        if UIApplication.shared.applicationState != .active { postBackgroundAlert() }
        countdown?.cancel()
        countdown = Task { [weak self] in
            var left: Int = CrashMonitor.countdownSeconds
            while left > 0 {
                let generator: UINotificationFeedbackGenerator = UINotificationFeedbackGenerator()
                generator.notificationOccurred(.warning)
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                left -= 1
                self?.phase = .countdown(secondsLeft: left)
            }
            self?.expire()
        }
    }

    /// "I'm OK".
    func cancel() {
        countdown?.cancel()
        countdown = nil
        phase = .idle
        detector.reset()
        UIApplication.shared.isIdleTimerDisabled = false
        clearBackgroundAlert()
    }

    private func expire() {
        countdown = nil
        let alerted: Bool = onExpire?() ?? false
        phase = .expired(alerted: alerted)
        clearBackgroundAlert()
    }

    func dismissExpired() {
        phase = .idle
        UIApplication.shared.isIdleTimerDisabled = false
    }

    private static let alertId: String = "crash-countdown"

    private func postBackgroundAlert() {
        let content: UNMutableNotificationContent = UNMutableNotificationContent()
        content.title = "Possible crash detected"
        content.body = "Open Convoyage and tap I'm OK within \(Self.countdownSeconds) seconds, or your convoy is told."
        content.sound = UNNotificationSound.default
        content.interruptionLevel = .timeSensitive
        let request: UNNotificationRequest = UNNotificationRequest(identifier: Self.alertId, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { (_: Error?) in }
    }

    private func clearBackgroundAlert() {
        let center: UNUserNotificationCenter = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [Self.alertId])
        center.removeDeliveredNotifications(withIdentifiers: [Self.alertId])
    }

    #if DEBUG
    /// DEBUG menu: pretend a crash was detected.
    func debugTrigger() {
        begin()
    }
    #endif
}
