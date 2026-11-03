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
    var onDisconnect: (@Sendable (String?) -> Void)?

    func room(_ room: LiveKit.Room, didUpdateSpeakingParticipants participants: [Participant]) {
        var out: [VoiceSpeaker] = []
        for p in participants {
            if p is LocalParticipant { continue }
            out.append(VoiceSpeaker(id: p.identity?.stringValue ?? "", name: p.name ?? ""))
        }
        onSpeakers?(out)
    }

    func room(_ room: LiveKit.Room, didDisconnectWithError error: LiveKitError?) {
        onDisconnect?(error?.localizedDescription)
    }
}

/// Push-to-talk over a LiveKit room named `convoy-<code>`.
///
/// The server only offers LiveKit over TCP (no UDP forwarding), so the SDK's own TCP fallback does the work; nothing
/// here changes the ICE settings. The microphone stays muted until the button is held. The audio session belongs to
/// LiveKit (play and record, Bluetooth allowed for helmet comms); this class never configures it.
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

    @ObservationIgnored private let api: ApiClient
    @ObservationIgnored private let bridge: VoiceBridge = VoiceBridge()
    @ObservationIgnored private var room: LiveKit.Room?
    @ObservationIgnored private var code: String?
    @ObservationIgnored private var connectTask: Task<Void, Never>?
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
        bridge.onDisconnect = { [weak self] (message: String?) in
            Task { @MainActor in
                self?.connectionLost(message)
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
        let result: ApiResult<VoiceTokenDto> = await api.voiceToken(convoyCode: target)
        if Task.isCancelled || code != target { return }
        switch result {
        case .failure(let f):
            state = .unavailable(VoiceChannel.reason(status: f.status, fallback: f.message))
        case .ok(let t):
            let r: LiveKit.Room = LiveKit.Room(delegate: bridge,
                                         connectOptions: ConnectOptions(autoSubscribe: true, reconnectAttempts: 8),
                                         roomOptions: RoomOptions())
            do {
                // The microphone is not enabled on connect: the track is only created when the button is first held.
                try await r.connect(url: t.url, token: t.token)
            } catch {
                await r.disconnect()
                if Task.isCancelled || code != target { return }
                state = .unavailable("Could not reach the voice server")
                return
            }
            if Task.isCancelled || code != target {
                await r.disconnect()
                return
            }
            room = r
            state = .ready
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

    private func connectionLost(_ message: String?) {
        // An intentional disconnect (leave) already cleared `room`.
        guard room != nil else { return }
        room = nil
        micOn = false
        desiredMic = false
        talking = false
        speakers = []
        state = .unavailable("Lost the voice connection")
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
