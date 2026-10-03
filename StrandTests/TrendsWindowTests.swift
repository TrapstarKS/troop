import XCTest
@testable import Strand

final class TrendsWindowTests: XCTestCase {
    func testCalendarPeriodsKeepLeapDaysAndNeverExtendIntoTheFuture() {
        let cases: [(Int, Int, String, String, String)] = [
            (7, 0, "2026-10-02", "2026-09-28", "2026-10-02"),
            (7, -1, "2026-10-02", "2026-09-21", "2026-09-27"),
            (7, 1, "2026-10-02", "2026-09-28", "2026-10-02"),
            (7, 0, "2025-01-01", "2024-12-30", "2025-01-01"),
            (30, -1, "2024-03-01", "2024-02-01", "2024-02-29"),
            (30, 0, "2024-02-29", "2024-02-01", "2024-02-29"),
            (30, -2, "2025-01-31", "2024-11-01", "2024-11-30"),
            (180, 0, "2026-01-01", "2025-08-01", "2026-01-01"),
            (180, -1, "2026-10-02", "2025-11-01", "2026-04-30"),
        ]
        for (days, offset, today, start, end) in cases {
            XCTAssertEqual(TrendsWindow.period(days: days, offset: offset, today: today),
                           TrendsWindow(start: start, end: end))
        }
        XCTAssertNil(TrendsWindow.period(days: 30, offset: 0, today: "2024-02-30"))
        XCTAssertNil(TrendsWindow.period(days: 7, offset: 0, today: "invalid"))
    }

    func testNavigationStopsAtThePeriodContainingTheEarliestReading() {
        for (days, expected) in [(7, -31), (30, -7), (180, -1)] {
            let minimum = TrendsWindow.minimumOffset(days: days, earliest: "2026-03-01", today: "2026-10-02")
            XCTAssertEqual(minimum, expected)
            XCTAssertTrue(TrendsWindow.period(days: days, offset: minimum, today: "2026-10-02")!.contains("2026-03-01"))
        }
        XCTAssertEqual(TrendsWindow.minimumOffset(days: 30, earliest: nil, today: "2026-10-02"), 0)
        let current = TrendsWindow.period(days: 7, offset: 0, today: "2026-10-02")!
        XCTAssertTrue(current.contains("2026-10-02"))
        XCTAssertFalse(current.contains("2026-10-03"))
        XCTAssertFalse(current.contains("2026-09-27"))
    }
}
