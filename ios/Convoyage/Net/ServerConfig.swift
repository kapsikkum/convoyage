import Foundation

/// Where the backend lives. The user can change it (Welcome and Profile); the default is production.
enum ServerConfig {
    static let defaultURLString: String = "https://convoy.24thframe.media"
    private static let key: String = "serverURL"

    /// The saved server URL, or the production default.
    static var urlString: String {
        let saved: String = UserDefaults.standard.string(forKey: key) ?? ""
        let trimmed: String = saved.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? defaultURLString : trimmed
    }

    static var baseURL: URL {
        return URL(string: urlString) ?? URL(string: defaultURLString)!
    }

    /// Trims, adds http:// when no scheme was typed, drops trailing slashes.
    static func normalize(_ raw: String) -> String {
        var s: String = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return "" }
        if !s.contains("://") { s = "http://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    /// Saves a new server URL (empty resets to the default). Returns the URL now in force.
    @discardableResult
    static func save(_ raw: String) -> String {
        let clean: String = normalize(raw)
        if clean.isEmpty || clean == defaultURLString {
            UserDefaults.standard.removeObject(forKey: key)
        } else {
            UserDefaults.standard.set(clean, forKey: key)
        }
        return urlString
    }

    private static func encodeQueryValue(_ v: String) -> String {
        var allowed: CharacterSet = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return v.addingPercentEncoding(withAllowedCharacters: allowed) ?? v
    }

    /// `<base>/<path>?query`. Query values are percent-encoded strictly (cursors, bbox).
    static func url(path: String, query: [URLQueryItem] = []) -> URL? {
        var s: String = urlString
        while s.hasSuffix("/") { s.removeLast() }
        let p: String = path.hasPrefix("/") ? path : "/" + path
        var full: String = s + p
        if !query.isEmpty {
            let parts: [String] = query.map { (q: URLQueryItem) -> String in
                return encodeQueryValue(q.name) + "=" + encodeQueryValue(q.value ?? "")
            }
            full += "?" + parts.joined(separator: "&")
        }
        return URL(string: full)
    }

    /// An absolute URL for a server-relative path such as `/v1/media/<id>`.
    static func absolute(_ pathOrURL: String) -> URL? {
        if pathOrURL.hasPrefix("http://") || pathOrURL.hasPrefix("https://") {
            return URL(string: pathOrURL)
        }
        return url(path: pathOrURL)
    }

    /// ws(s)://host/ws. The JWT travels in the subprotocol, never in the URL.
    static func socketURL() -> URL? {
        guard let http = url(path: "/ws"), var comps = URLComponents(url: http, resolvingAgainstBaseURL: false) else { return nil }
        comps.scheme = (comps.scheme == "https") ? "wss" : "ws"
        return comps.url
    }

    /// Debug-only dev auth: /ws?dev_user=<id>&name=<name>.
    static func devSocketURL(userId: String, name: String) -> URL? {
        let items: [URLQueryItem] = [URLQueryItem(name: "dev_user", value: userId), URLQueryItem(name: "name", value: name)]
        guard let http = url(path: "/ws", query: items),
              var comps = URLComponents(url: http, resolvingAgainstBaseURL: false) else { return nil }
        comps.scheme = (comps.scheme == "https") ? "wss" : "ws"
        return comps.url
    }
}
