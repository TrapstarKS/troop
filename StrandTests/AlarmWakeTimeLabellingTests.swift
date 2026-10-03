import XCTest
@testable import Strand

final class AlarmWakeTimeLabellingTests: XCTestCase {
    func testAlarmSummaryResolvesOnceFromTimelineClock() throws {
        let source = try alarmSource()
        XCTAssertEqual(source.components(separatedBy: "model.sleepPlannerSnapshot(from: tick.date)").count - 1, 1)
        XCTAssertTrue(source.contains("planCard(snapshot, now: tick.date)"))
        XCTAssertTrue(source.contains("if snapshot.alarmConfirmed {"))
        XCTAssertFalse(source.contains("heroTime(label: \"Wake\","))
        XCTAssertFalse(source.contains("WindDownNudge.wakeMinutes"))
    }

    func testControlsDistinguishAlarmDeadlineFromSuggestedBedtime() throws {
        let source = try alarmSource()
        XCTAssertTrue(source.contains("Suggested bedtime"))
        XCTAssertTrue(source.contains(".accessibilityLabel(\"Alarm deadline\")"))
        XCTAssertFalse(source.contains(".accessibilityLabel(\"Wake time\")"))
        XCTAssertTrue(source.contains("These times move your strap alarm AND the evening reminder"))
    }

    func testOverrideIsUsedForTheActualNextWake() throws {
        let calendar = utcCalendar()
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 16, hour: 9)))
        let next = try XCTUnwrap(AppModel.nextSmartAlarmDate(minutes: 600, weekdays: [7],
                                                             overrides: [7: 1230], from: now, calendar: calendar))
        let parts = calendar.dateComponents([.weekday, .hour, .minute], from: next)
        XCTAssertEqual(parts.weekday, 7)
        XCTAssertEqual(parts.hour, 20)
        XCTAssertEqual(parts.minute, 30)
    }

    private func utcCalendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private func alarmSource() throws -> String {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf: root.appendingPathComponent("Strand/Screens/SmartAlarmView.swift"), encoding: .utf8)
    }
}
