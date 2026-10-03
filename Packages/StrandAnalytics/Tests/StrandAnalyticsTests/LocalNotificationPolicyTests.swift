import XCTest
@testable import StrandAnalytics

final class LocalNotificationPolicyTests: XCTestCase {
    func testDeliveryRequiresAllGatesAndUnconsumedRecordedEvent() {
        for enabled in [false, true] {
            for authorized in [false, true] {
                for quiet in [false, true] {
                    XCTAssertEqual(LocalNotificationPolicy.shouldDeliver(
                        enabled: enabled, authorized: authorized, quiet: quiet,
                        eventKey: "night", lastEventKey: nil, occurrenceSec: 1000, nowSec: 1100),
                        enabled && authorized && !quiet)
                }
            }
        }
        for occurrence in [nil, 1101, -85_301] as [Int?] {
            XCTAssertFalse(LocalNotificationPolicy.shouldDeliver(enabled: true, authorized: true, quiet: false,
                eventKey: "night", lastEventKey: nil, occurrenceSec: occurrence, nowSec: 1100))
        }
        XCTAssertFalse(LocalNotificationPolicy.shouldDeliver(enabled: true, authorized: true, quiet: false,
            eventKey: "night", lastEventKey: "night", occurrenceSec: 1000, nowSec: 1100))
        XCTAssertTrue(LocalNotificationPolicy.shouldDeliver(enabled: true, authorized: true, quiet: false,
            eventKey: "night", lastEventKey: nil, occurrenceSec: 0, nowSec: 86_400))
    }

    func testExpiredAndInvalidKeysStayUndeliverable() {
        for key in [nil, ""] as [String?] {
            XCTAssertFalse(LocalNotificationPolicy.shouldDeliver(enabled: true, authorized: true, quiet: false,
                eventKey: key, lastEventKey: nil, occurrenceSec: 0, nowSec: 1100))
        }
        XCTAssertFalse(LocalNotificationPolicy.shouldDeliver(enabled: true, authorized: true, quiet: false,
            eventKey: "night", lastEventKey: nil, occurrenceSec: 0, nowSec: 86_401))
        XCTAssertFalse(LocalNotificationPolicy.shouldDeliver(enabled: true, authorized: true, quiet: false,
            eventKey: "night", lastEventKey: nil, occurrenceSec: 0, nowSec: -1))
        XCTAssertFalse(LocalNotificationPolicy.shouldDeliver(enabled: true, authorized: true, quiet: false,
            eventKey: "night", lastEventKey: nil, occurrenceSec: 0, nowSec: 1, maxAgeSec: -1))
    }

    func testQuietHoursWrapAndEqualTimes() {
        for minute in 0..<1440 {
            XCTAssertEqual(LocalNotificationPolicy.isQuiet(minute: minute, start: 1320, end: 420, enabled: true), minute >= 1320 || minute < 420)
            XCTAssertFalse(LocalNotificationPolicy.isQuiet(minute: minute, start: 420, end: 420, enabled: true))
            XCTAssertFalse(LocalNotificationPolicy.isQuiet(minute: minute, start: 420, end: 420, enabled: false))
        }
    }

}
