import Foundation
import Security

/// The app's secrets (server device token, TickTick cookie), never logged or sent anywhere else.
///
/// Stored in a file only this user can read (`<Application Support>/ZenTaskSecrets/<service>.json`, mode 0600),
/// not in the Keychain: ZenTask is self-signed without an Apple Team ID, and macOS gives such apps' keychain
/// items a per-build "partition" that only the keychain password can change, so every update asked for
/// access again (twice — one prompt per item). The owner chose no prompts with the same protection as the
/// ZenTask database file (2026-11-08). Items left in the Keychain by older versions are moved over once.
struct Keychain: Sendable {
    let service: String
    let directory: URL

    enum Account {
        static let serverToken = "serverToken"
        static let ticktickCookie = "ticktickCookie"
    }

    struct Failure: Error, LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    init(service: String, directory: URL? = nil) {
        self.service = service
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ZenTaskSecrets", isDirectory: true)
    }

    private var file: URL { directory.appendingPathComponent(service + ".json") }

    func read(_ account: String) -> String? {
        if let value = load()[account] { return value }
        // Moved over from the Keychain on first read (older versions kept secrets there).
        guard let legacy = legacyRead(account) else { return nil }
        if (try? write(legacy, for: account)) != nil { legacyDelete(account) }
        return legacy
    }

    func write(_ value: String, for account: String) throws {
        var all = load()
        all[account] = value
        try save(all)
    }

    func delete(_ account: String) {
        var all = load()
        if all.removeValue(forKey: account) != nil { try? save(all) }
        legacyDelete(account)
    }

    // MARK: File

    private func load() -> [String: String] {
        guard let data = try? Data(contentsOf: file) else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
    }

    private func save(_ all: [String: String]) throws {
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
            let data = try JSONEncoder().encode(all)
            // Create with 0600 before any secret is written, then replace atomically.
            let tmp = directory.appendingPathComponent(".\(service).\(UUID().uuidString).tmp")
            guard fm.createFile(atPath: tmp.path, contents: data, attributes: [.posixPermissions: 0o600]) else {
                throw Failure(message: "Couldn't save the sign-in details")
            }
            _ = try fm.replaceItemAt(file, withItemAt: tmp)
            try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        } catch let error as Failure {
            throw error
        } catch {
            throw Failure(message: "Couldn't save the sign-in details (\(error.localizedDescription))")
        }
    }

    // MARK: Legacy Keychain items

    private func legacyQuery(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    private func legacyRead(_ account: String) -> String? {
        var query = legacyQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func legacyDelete(_ account: String) {
        SecItemDelete(legacyQuery(account) as CFDictionary)
    }
}
