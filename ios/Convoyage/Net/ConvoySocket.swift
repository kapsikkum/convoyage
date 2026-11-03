import Foundation

/// Server location, read from Info.plist (ConvoyageServerURL).
enum ServerConfig {
    static var baseURL: URL {
        let raw: String = (Bundle.main.object(forInfoDictionaryKey: "ConvoyageServerURL") as? String) ?? "http://localhost:8080"
        return URL(string: raw) ?? URL(string: "http://localhost:8080")!
    }

    /// ws(s)://host:port/ws?dev_user=<uuid>&name=<name>
    /// TODO(auth): replace dev_user with a real session token once the server has one.
    static func socketURL(userId: String, name: String) -> URL? {
        guard var comps = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else { return nil }
        comps.scheme = (comps.scheme == "https") ? "wss" : "ws"
        comps.path = "/ws"
        comps.queryItems = [
            URLQueryItem(name: "dev_user", value: userId),
            URLQueryItem(name: "name", value: name)
        ]
        return comps.url
    }
}

enum SocketState: Equatable {
    case idle
    case connecting
    case open
    case closed(String)
}

/// One WebSocket to /ws. Callbacks run on the main actor.
@MainActor
final class ConvoySocket {
    var onMessage: ((ServerMessage) -> Void)?
    var onState: ((SocketState) -> Void)?

    private var task: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private let session: URLSession = URLSession(configuration: .default)
    private let decoder: JSONDecoder = JSONDecoder()

    func connect(userId: String, name: String) {
        disconnect()
        guard let url = ServerConfig.socketURL(userId: userId, name: name) else {
            onState?(.closed("Bad server URL"))
            return
        }
        onState?(.connecting)
        let t: URLSessionWebSocketTask = session.webSocketTask(with: url)
        task = t
        t.resume()
        receiveTask = Task { [weak self] in
            await self?.receiveLoop(t)
        }
        pingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                if Task.isCancelled { break }
                self?.send(.ping)
            }
        }
    }

    func disconnect() {
        receiveTask?.cancel()
        pingTask?.cancel()
        receiveTask = nil
        pingTask = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    func send(_ message: ClientMessage) {
        guard let t = task, let text = message.jsonString() else { return }
        t.send(.string(text)) { (_: Error?) in }
    }

    private func receiveLoop(_ t: URLSessionWebSocketTask) async {
        var announcedOpen: Bool = false
        while !Task.isCancelled {
            do {
                let frame: URLSessionWebSocketTask.Message = try await t.receive()
                if !announcedOpen {
                    announcedOpen = true
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
                if !Task.isCancelled {
                    onState?(.closed(error.localizedDescription))
                }
                return
            }
        }
    }
}
