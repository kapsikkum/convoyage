import Foundation
import AVFoundation

/// Spoken turn prompts (Australian English). While it talks, other audio (music, podcasts) is ducked.
///
/// The audio session is left alone while LiveKit has taken it for talking (play and record, Bluetooth allowed): the
/// prompts then simply play through it. Otherwise it is set to playback and ducks other audio while a prompt speaks.
final class NavVoice: NSObject, AVSpeechSynthesizerDelegate {
    private let synth: AVSpeechSynthesizer = AVSpeechSynthesizer()
    private var pending: Int = 0

    var muted: Bool = false {
        didSet {
            if muted { stop() }
        }
    }

    /// True while the push-to-talk voice channel has the session in play-and-record.
    private var sessionOwnedElsewhere: Bool {
        return AVAudioSession.sharedInstance().category == .playAndRecord
    }

    override init() {
        super.init()
        synth.delegate = self
    }

    func speak(_ text: String) {
        if muted { return }
        activateSession()
        let u: AVSpeechUtterance = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "en-AU") ?? AVSpeechSynthesisVoice(language: "en-GB")
        u.rate = AVSpeechUtteranceDefaultSpeechRate
        u.volume = 1.0
        u.preUtteranceDelay = 0.05
        pending += 1
        synth.speak(u)
    }

    func stop() {
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        pending = 0
        deactivateSession()
    }

    // MARK: Audio session

    private func activateSession() {
        if sessionOwnedElsewhere { return }
        let session: AVAudioSession = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
            try session.setActive(true)
        } catch {
            // Speaking without ducking is still better than not speaking.
        }
    }

    private func deactivateSession() {
        if sessionOwnedElsewhere { return }
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        } catch {
            // Other audio will resume when it can.
        }
    }

    // MARK: AVSpeechSynthesizerDelegate

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        pending = max(0, pending - 1)
        if pending == 0 { deactivateSession() }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        pending = max(0, pending - 1)
        if pending == 0 { deactivateSession() }
    }
}
