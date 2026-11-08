import XCTest
@testable import ZenTask
@preconcurrency import ZenTaskShared

@MainActor
final class AppModelTests: XCTestCase {
    private func model() -> AppModel {
        AppModel(core: ZenTaskCore.companion.open(dbPath: nil), keychain: Keychain(service: "com.vuhpquang.zentask.tests.model", directory: FileManager.default.temporaryDirectory.appendingPathComponent("zt-model-secrets")))
    }

    private func plan(needsConfirmation: Bool) -> SyncPlan {
        SyncPlan(created: 0, updated: 0, trashed: 42, restored: 0, untouchedRemoved: 0, needsConfirmation: needsConfirmation)
    }

    func testGuardedSyncAsksThenAppliesWhenConfirmed() async {
        let m = model()
        m.pending = AppModel.PendingSync(snapshot: TickTickSnapshot.companion.empty(inboxId: "inbox1"), plan: plan(needsConfirmation: true))
        await m.applyTickTick(confirmed: false)
        XCTAssertTrue(m.confirmMassTrash, "asks first")
        XCTAssertNotNil(m.pending, "nothing applied yet")
        m.confirmMassTrash = false // the dialog dismisses itself before the button's action runs
        await m.applyTickTick(confirmed: true)
        XCTAssertNil(m.pending, "applied after confirming")
        XCTAssertNil(m.ticktickError)
        XCTAssertEqual(m.ticktickMessage?.hasPrefix("Done"), true)
    }

    func testUnguardedSyncAppliesDirectly() async {
        let m = model()
        m.pending = AppModel.PendingSync(snapshot: TickTickSnapshot.companion.empty(inboxId: "inbox1"), plan: plan(needsConfirmation: false))
        await m.applyTickTick(confirmed: false)
        XCTAssertFalse(m.confirmMassTrash)
        XCTAssertNil(m.pending)
    }
}
