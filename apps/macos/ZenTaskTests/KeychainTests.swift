import XCTest
@testable import ZenTask

final class KeychainTests: XCTestCase {
    private let keychain = Keychain(service: "com.vuhpquang.zentask.tests")

    override func tearDown() {
        keychain.delete("probe")
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
}
