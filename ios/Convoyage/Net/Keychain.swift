import Foundation
import Security

/// An access JWT and the refresh token that goes with it.
struct Tokens: Codable, Equatable {
    var access: String
    var refresh: String
}

/// The session tokens, kept in the Keychain. Reads are cached (they happen on every request); writes are durable.
/// If the Keychain refuses (some unsigned builds), the cache keeps the session alive until the app quits.
final class TokenStore: @unchecked Sendable {
    private let lock: NSLock = NSLock()
    private var cache: Tokens?
    private var loaded: Bool = false
    private let service: String = "app.convoyage.session"
    private let account: String = "tokens"

    func load() -> Tokens? {
        lock.lock()
        defer { lock.unlock() }
        if !loaded {
            cache = readKeychain()
            loaded = true
        }
        return cache
    }

    func save(_ tokens: Tokens?) {
        lock.lock()
        defer { lock.unlock() }
        cache = tokens
        loaded = true
        if let t = tokens {
            writeKeychain(t)
        } else {
            deleteKeychain()
        }
    }

    private func baseQuery() -> [String: Any] {
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    private func readKeychain() -> Tokens? {
        var query: [String: Any] = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status: OSStatus = SecItemCopyMatching(query as CFDictionary, &out)
        guard status == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(Tokens.self, from: data)
    }

    private func writeKeychain(_ tokens: Tokens) {
        guard let data = try? JSONEncoder().encode(tokens) else { return }
        let update: [String: Any] = [kSecValueData as String: data]
        let status: OSStatus = SecItemUpdate(baseQuery() as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add: [String: Any] = baseQuery()
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            _ = SecItemAdd(add as CFDictionary, nil)
        }
    }

    private func deleteKeychain() {
        _ = SecItemDelete(baseQuery() as CFDictionary)
    }
}

/// Just enough JWT reading to know when an access token runs out.
enum Jwt {
    /// The `exp` claim in epoch seconds, or nil if this is not a readable JWT.
    static func expiresAt(_ jwt: String) -> TimeInterval? {
        let parts: [Substring] = jwt.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var b64: String = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let exp = obj["exp"] as? NSNumber else { return nil }
        return exp.doubleValue
    }

    /// True if the token is expired or will be within `seconds`. Unreadable tokens count as expiring.
    static func expiresWithin(_ jwt: String, seconds: TimeInterval) -> Bool {
        guard let exp = expiresAt(jwt) else { return true }
        return exp - Date().timeIntervalSince1970 <= seconds
    }
}
