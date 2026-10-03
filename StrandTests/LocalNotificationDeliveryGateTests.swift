import XCTest
@testable import Strand

final class LocalNotificationDeliveryGateTests: XCTestCase {
    func testOverlappingReadingsClaimOneAttempt() {
        let gate = LocalNotificationDeliveryGate()
        XCTAssertNotNil(gate.begin("battery-low"))
        XCTAssertNil(gate.begin("battery-low"))
        XCTAssertNotNil(gate.begin("battery-full"))
    }

    func testRejectedDeliveryDoesNotConsumeTheCrossing() {
        let gate = LocalNotificationDeliveryGate()
        let token = gate.begin("battery-low")!
        var marker = false
        gate.finish("battery-low", token: token, accepted: false, onAccepted: { marker = true }, onStale: { XCTFail() })
        XCTAssertFalse(marker)
        XCTAssertNotNil(gate.begin("battery-low"))
    }

    func testAcceptedDeliveryCommitsOnce() {
        let gate = LocalNotificationDeliveryGate()
        let token = gate.begin("battery-low")!
        var commits = 0
        gate.finish("battery-low", token: token, accepted: true, onAccepted: { commits += 1 }, onStale: {})
        gate.finish("battery-low", token: token, accepted: false, onAccepted: { commits += 1 }, onStale: {})
        XCTAssertEqual(commits, 1)
    }

    func testRearmRejectsAnOlderCompletionAndClearsItsAlert() {
        let gate = LocalNotificationDeliveryGate()
        let token = gate.begin("battery-low")!
        gate.invalidate("battery-low")
        XCTAssertFalse(gate.isCurrent("battery-low", token: token))
        var cleared = false
        gate.finish("battery-low", token: token, accepted: true, onAccepted: { XCTFail() }, onStale: { cleared = true })
        XCTAssertTrue(cleared)
        XCTAssertNotNil(gate.begin("battery-low"))
    }

    func testOldCompletionCannotClearANewerAcceptedDelivery() {
        let gate = LocalNotificationDeliveryGate()
        let old = gate.begin("battery-low")!
        gate.invalidate("battery-low")
        let new = gate.begin("battery-low")!
        var commits = 0
        gate.finish("battery-low", token: new, accepted: true, onAccepted: { commits += 1 }, onStale: { XCTFail() })
        gate.finish("battery-low", token: old, accepted: true, onAccepted: { XCTFail() }, onStale: { XCTFail() })
        XCTAssertEqual(commits, 1)
        XCTAssertNotNil(gate.begin("battery-low"))
    }

    func testOldCompletionCannotCommitOrClearANewerAttempt() {
        let gate = LocalNotificationDeliveryGate()
        let old = gate.begin("battery-low")!
        gate.invalidate("battery-low")
        let new = gate.begin("battery-low")!
        gate.finish("battery-low", token: old, accepted: true, onAccepted: { XCTFail() }, onStale: { XCTFail() })
        XCTAssertTrue(gate.isCurrent("battery-low", token: new))
    }
}
