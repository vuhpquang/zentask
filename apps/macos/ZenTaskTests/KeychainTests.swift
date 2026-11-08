import XCTest
@testable import ZenTask

final class KeychainTests: XCTestCase {
    private var dir: URL!
    private var keychain: Keychain!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("zt-secrets-\(UUID().uuidString)")
        keychain = Keychain(service: "com.vuhpquang.zentask.tests", directory: dir)
    }

    override func tearDown() {
        keychain.delete("probe")
        try? FileManager.default.removeItem(at: dir)
    }

    func testRoundTripAndDelete() throws {
        XCTAssertNil(keychain.read("probe"))
        try keychain.write("first", for: "probe")
        XCTAssertEqual(keychain.read("probe"), "first")
        try keychain.write("second ✓", for: "probe")
        XCTAssertEqual(keychain.read("probe"), "second ✓")
        keychain.delete("probe")
        XCTAssertNil(keychain.read("probe"))
    }

    func testSecretsFileIsPrivate() throws {
        try keychain.write("secret", for: "probe")
        let file = dir.appendingPathComponent("com.vuhpquang.zentask.tests.json")
        let fileMode = try FileManager.default.attributesOfItem(atPath: file.path)[.posixPermissions] as? Int
        let dirMode = try FileManager.default.attributesOfItem(atPath: dir.path)[.posixPermissions] as? Int
        XCTAssertEqual(fileMode, 0o600)
        XCTAssertEqual(dirMode, 0o700)
    }

    func testOldKeychainItemIsMovedOnceAndRemoved() throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.vuhpquang.zentask.tests",
                                    kSecAttrAccount as String: "probe"]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = Data("legacy".utf8)
        XCTAssertEqual(SecItemAdd(add as CFDictionary, nil), errSecSuccess)
        XCTAssertEqual(keychain.read("probe"), "legacy")
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, nil), errSecItemNotFound, "removed from the Keychain")
        XCTAssertEqual(Keychain(service: "com.vuhpquang.zentask.tests", directory: dir).read("probe"), "legacy", "now in the file")
    }
}
