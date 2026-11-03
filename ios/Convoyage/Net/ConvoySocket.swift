import Foundation

enum SocketState: Equatable {
    case idle
    case connecting
    case open
    case closed(String)
}

/// How one socket connection ended.
struct SocketEnd {
    /// The server spoke at least once (the handshake succeeded).
    var opened: Bool
    /// HTTP status of a refused handshake (401 = bad or expired token), when there was one.
    var httpStatus: Int?
    /// WebSocket close code (4401 = the session ended, do not retry).
    var closeCode: Int
    var message: String
}

/// The subprotocol the server echoes; the `bearer.<jwt>` entry offered next to it only carries the token.
/// Exact header: `Sec-WebSocket-Protocol: convoyage.v1, bearer.<jwt>` (server/src/auth.ts socketToken).
let wsProtocolName: String = "convoyage.v1"

/// Close code the server uses when the session ended (no point retrying).
let closeSessionEnded: Int = 4401

/// One WebSocket to /ws at a time. Callbacks run on the main actor.
@MainActor
final class ConvoySocket {
    var onMessage: ((ServerMessage) -> Void)?
    var onState: ((SocketState) -> Void)?

    private var task: URLSessionWebSocketTask?
    private let session: URLSession = URLSession(configuration: .default)
    private let decoder: JSONDecoder = JSONDecoder()

    /// Connects and reads until the connection ends (or `close()` is called). Returns how it ended.
    func run(url: URL, protocols: [String]) async -> SocketEnd {
        let t: URLSessionWebSocketTask
        if protocols.isEmpty {
            t = session.webSocketTask(with: url)
        } else {
            t = session.webSocketTask(with: url, protocols: protocols)
        }
        task = t
        onState?(.connecting)
        t.resume()

        let pinger: Task<Void, Never> = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                if Task.isCancelled { break }
                self?.send(.ping)
            }
        }

        var opened: Bool = false
        var message: String = ""
        while !Task.isCancelled {
            do {
                let frame: URLSessionWebSocketTask.Message = try await t.receive()
                if !opened {
                    opened = true
                    onState?(.open)
                }
                var data: Data? = nil
                switch frame {
                case .string(let s): data = s.data(using: .utf8)
                case .data(let d): data = d
                @unknown default: data = nil
                }
                if let data = data, let msg = try? decoder.decode(ServerMessage.self, from: data) {
                    onMessage?(msg)
                }
            } catch {
                message = error.localizedDescription
                break
            }
        }
        pinger.cancel()

        let status: Int? = (t.response as? HTTPURLResponse)?.statusCode
        let code: Int = t.closeCode.rawValue
        if task === t { task = nil }
        t.cancel(with: .goingAway, reason: nil)
        onState?(.closed(message.isEmpty ? "Disconnected" : message))
        return SocketEnd(opened: opened, httpStatus: status, closeCode: code, message: message)
    }

    /// Drops the live connection; `run` then returns.
    func close() {
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    func send(_ message: ClientMessage) {
        guard let t = task, let text = message.jsonString() else { return }
        t.send(.string(text)) { (_: Error?) in }
    }
}
