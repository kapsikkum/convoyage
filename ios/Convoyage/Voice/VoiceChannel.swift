import Foundation
import AVFoundation
import Observation
import LiveKit

/// Where push-to-talk stands.
enum VoiceState: Equatable {
    /// Not in a convoy (or signed out).
    case off
    case connecting
    /// Connected to the LiveKit room with the microphone muted.
    case ready
    /// Could not connect; everything else in the app keeps working. The text says why.
    case unavailable(String)
}

struct VoiceSpeaker: Equatable {
    var id: String
    var name: String
}

/// Receives LiveKit callbacks (on any thread) and hands plain values to closures.
private final class VoiceBridge: NSObject, RoomDelegate, @unchecked Sendable {
    var onSpeakers: (@Sendable ([VoiceSpeaker]) -> Void)?
    var onDisconnect: (@Sendable (LiveKit.Room, String?) -> Void)?
    var onConnectionState: (@Sendable (LiveKit.Room, LiveKit.ConnectionState) -> Void)?

    func room(_ room: LiveKit.Room, didUpdateSpeakingParticipants participants: [Participant]) {
        var out: [VoiceSpeaker] = []
        for p in participants {
            if p is LocalParticipant { continue }
            out.append(VoiceSpeaker(id: p.identity?.stringValue ?? "", name: p.name ?? ""))
        }
        onSpeakers?(out)
    }

    func room(_ room: LiveKit.Room, didUpdateConnectionState connectionState: LiveKit.ConnectionState, from oldConnectionState: LiveKit.ConnectionState) {
        onConnectionState?(room, connectionState)
    }

    func room(_ room: LiveKit.Room, didDisconnectWithError error: LiveKitError?) {
        onDisconnect?(room, error?.localizedDescription)
    }
}

/// Push-to-talk over a LiveKit room named `convoy-<code>`.
///
/// The server only offers LiveKit over TCP (no UDP forwarding), so the SDK's own TCP fallback does the work; nothing
/// here changes the ICE settings. The microphone stays muted until the button is held. The audio session belongs to
/// LiveKit (play and record, Bluetooth allowed for helmet comms); this class never configures it except to put the
/// session back into that voice category if a reconnect finds something else (a spoken prompt) holding it.
@Observable
@MainActor
final class VoiceChannel {
    var state: VoiceState = .off
    /// True while the button is held and the microphone is (being) unmuted.
    var talking: Bool = false
    /// Other members who are speaking right now.
    var speakers: [VoiceSpeaker] = []
    /// One-off message for the person ("Microphone access is off").
    var notice: String?
    /// The person closed the "Voice unavailable" banner for this convoy.
    var bannerDismissed: Bool = false

    /// Auto-reconnect pace: 1 s, 2 s, 4 s ... capped at 30 s, then give up so the banner offers Retry.
    private static let reconnectLimit: Int = 10
    private static let reconnectMaxDelayS: Double = 30

    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private let bridge: VoiceBridge = VoiceBridge()
    @ObservationIgnored private var room: LiveKit.Room?
    @ObservationIgnored private var code: String?
    @ObservationIgnored private var connectTask: Task<Void, Never>?
    @ObservationIgnored private var reconnectTask: Task<Void, Never>?
    @ObservationIgnored private var reconnectAttempts: Int = 0
    @ObservationIgnored private var micOn: Bool = false
    @ObservationIgnored private var desiredMic: Bool = false
    @ObservationIgnored private var applying: Bool = false

    init(api: ApiClient) {
        self.api = api
        bridge.onSpeakers = { [weak self] (s: [VoiceSpeaker]) in
            Task { @MainActor in
                self?.speakers = s
            }
        }
        bridge.onDisconnect = { [weak self] (r: LiveKit.Room, message: String?) in
            Task { @MainActor in
                self?.connectionLost(r, message)
            }
        }
        bridge.onConnectionState = { [weak self] (r: LiveKit.Room, connection: LiveKit.ConnectionState) in
            Task { @MainActor in
                self?.connectionStateChanged(r, connection)
            }
        }
    }

    // MARK: Connect and leave

    /// Joins the voice room of `code` (a no-op if already there, on the way, or failed: use `retry`).
    func join(code newCode: String) {
        if code == newCode { return }
        leave()
        code = newCode
        state = .connecting
        bannerDismissed = false
        connectTask = Task { [weak self] in
            await self?.connect(newCode)
        }
    }

    func retry() {
        guard let c = code else { return }
        code = nil
        join(code: c)
    }

    func leave() {
        connectTask?.cancel()
        connectTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        reconnectAttempts = 0
        code = nil
        state = .off
        speakers = []
        talking = false
        notice = nil
        micOn = false
        desiredMic = false
        let old: LiveKit.Room? = room
        room = nil
        if let r = old {
            Task { await r.disconnect() }
        }
    }

    private func connect(_ target: String) async {
        guard let r: LiveKit.Room = await openRoom(target) else { return }
        if Task.isCancelled || code != target {
            await r.disconnect()
            return
        }
        room = r
        state = .ready
    }

    /// Fetches a token and joins the room; nil when that failed (with `state` saying why unless `quiet`).
    private func openRoom(_ target: String, quiet: Bool = false) async -> LiveKit.Room? {
        let result: ApiResult<VoiceTokenDto> = await api.voiceToken(convoyCode: target)
        if Task.isCancelled || code != target { return nil }
        switch result {
        case .failure(let f):
            if !quiet {
                state = .unavailable(VoiceChannel.reason(status: f.status, fallback: f.message))
            }
            return nil
        case .ok(let t):
            let r: LiveKit.Room = LiveKit.Room(delegate: bridge,
                                             connectOptions: ConnectOptions(autoSubscribe: true, reconnectAttempts: 8),
                                             roomOptions: RoomOptions())
            do {
                // The microphone is not enabled on connect: the track is only created when the button is first held.
                try await r.connect(url: t.url, token: t.token)
            } catch {
                await r.disconnect()
                if !quiet && !Task.isCancelled && code == target {
                    state = .unavailable("Could not reach the voice server")
                }
                return nil
            }
            return r
        }
    }

    private static func reason(status: Int, fallback: String) -> String {
        switch status {
        case 0: return "Could not reach the server"
        case 401: return "Sign in with your email to use voice"
        case 501: return "Voice is not set up on this server"
        default: return fallback
        }
    }

    /// A connection the SDK has given up on: forget it and start talking again on our own, slowly.
    private func connectionLost(_ dropped: LiveKit.Room, _ message: String?) {
        // An intentional disconnect (leave) already cleared `room`; a room we have replaced is not ours any more.
        guard dropped === room, let target: String = code else { return }
        room = nil
        micOn = false
        desiredMic = false
        talking = false
        speakers = []
        startReconnect(target)
    }

    /// LiveKit's own reconnect (quick or full) shows as connecting until it is back.
    private func connectionStateChanged(_ changed: LiveKit.Room, _ connection: LiveKit.ConnectionState) {
        guard changed === room else { return }
        switch connection {
        case .reconnecting:
            state = .connecting
            // The button is not held any more as far as the connection is concerned: close the mic so a drop
            // while talking cannot leave it open once the room is back.
            talking = false
            desiredMic = false
            reconcile()
        case .connected:
            state = .ready
        default:
            break
        }
    }

    /// Tries the room again with a capped exponential backoff (1 s, 2 s, 4 s ... 30 s, ten times), like the Android
    /// voice chat. Nothing else in the app waits on it.
    private func startReconnect(_ target: String) {
        if reconnectTask != nil { return }
        reconnectAttempts = 0
        state = .connecting
        reconnectTask = Task { [weak self] in
            await self?.reconnectLoop(target)
        }
    }

    private func reconnectLoop(_ target: String) async {
        while !Task.isCancelled, reconnectAttempts < VoiceChannel.reconnectLimit {
            reconnectAttempts += 1
            let delay: Double = min(VoiceChannel.reconnectMaxDelayS, pow(2.0, Double(reconnectAttempts - 1)))
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            if Task.isCancelled || code != target { return }
            state = .connecting
            guard let r: LiveKit.Room = await openRoom(target, quiet: true) else {
                if Task.isCancelled || code != target { return }
                continue
            }
            if Task.isCancelled || code != target {
                await r.disconnect()
                return
            }
            VoiceChannel.ensureVoiceAudioSession()
            room = r
            micOn = false
            desiredMic = false
            talking = false
            reconnectAttempts = 0
            reconnectTask = nil
            state = .ready
            return
        }
        if Task.isCancelled || code != target { return }
        reconnectTask = nil
        state = .unavailable("Lost the voice connection")
    }

    /// LiveKit takes the session as play-and-record when it connects; put it back if a spoken prompt left it in
    /// playback while the connection was down. A session that is already right is left untouched.
    private static func ensureVoiceAudioSession() {
        let session: AVAudioSession = AVAudioSession.sharedInstance()
        guard session.category != .playAndRecord else { return }
        // The SDK's own speaker preset, so a reconnected room sounds like one that just connected.
        let configuration: AudioSessionConfiguration = AudioSessionConfiguration.playAndRecordSpeaker
        do {
            try session.setCategory(configuration.category, mode: configuration.mode, options: configuration.categoryOptions)
            try session.setActive(true)
        } catch {
            // The connection still stands; the audio route sorts itself out on the next connect.
        }
    }

    // MARK: Push to talk

    /// The button went down (true) or up (false).
    func setTalking(_ down: Bool) {
        guard state == .ready else {
            talking = false
            return
        }
        talking = down
        desiredMic = down
        reconcile()
    }

    /// Makes the microphone match `desiredMic`, one change at a time, so a quick tap cannot leave it open.
    private func reconcile() {
        if applying { return }
        applying = true
        Task { [weak self] in
            await self?.applyMic()
        }
    }

    private func applyMic() async {
        while micOn != desiredMic, let r = room {
            let want: Bool = desiredMic
            if want {
                let allowed: Bool = await VoiceChannel.microphoneAllowed()
                if !allowed {
                    notice = "Microphone access is off. Turn it on in Settings to talk."
                    desiredMic = micOn
                    talking = false
                    break
                }
            }
            do {
                _ = try await r.localParticipant.setMicrophone(enabled: want)
                micOn = want
            } catch {
                notice = "The microphone could not be opened"
                desiredMic = micOn
                talking = false
                break
            }
        }
        applying = false
    }

    /// Asks for the microphone on the first press; later presses just read the answer.
    private static func microphoneAllowed() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        default:
            return await AVAudioApplication.requestRecordPermission()
        }
    }
}
