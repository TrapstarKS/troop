import XCTest
import StrandAnalytics
@testable import Strand

final class SleepPlannerAlarmTests: XCTestCase {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }

    private func date(_ day: Int, hour: Int = 6) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 9, day: day, hour: hour))!
    }

    func testSkipNextRetainsEveryFutureDay() throws {
        let next = try XCTUnwrap(AppModel.nextSmartAlarmDate(minutes: 420, weekdays: [], from: date(16), calendar: calendar))
        let skipped = AppModel.smartAlarmOccurrenceKey(next, calendar: calendar)
        let afterSkip = AppModel.nextSmartAlarmDate(minutes: 420, weekdays: [], skippedOccurrence: skipped,
                                                    from: date(16), calendar: calendar)
        XCTAssertEqual(afterSkip, date(17, hour: 7))
        XCTAssertEqual(AppModel.nextSmartAlarmDate(minutes: 420, weekdays: [], skippedOccurrence: skipped,
                                                   from: date(17), calendar: calendar), date(17, hour: 7))
    }

    func testSkipSingleWeeklyOccurrenceStillFindsFollowingWeek() {
        let key = AppModel.smartAlarmOccurrenceKey(date(16, hour: 7), calendar: calendar)
        XCTAssertEqual(AppModel.nextSmartAlarmDate(minutes: 420, weekdays: [4], skippedOccurrence: key,
                                                   from: date(16, hour: 8), calendar: calendar), date(23, hour: 7))
        let nextWeekKey = AppModel.smartAlarmOccurrenceKey(date(23, hour: 7), calendar: calendar)
        XCTAssertEqual(AppModel.nextSmartAlarmDate(minutes: 420, weekdays: [4], skippedOccurrence: nextWeekKey,
                                                   from: date(16, hour: 8), calendar: calendar), date(30, hour: 7))
    }

    func testSkipDoesNotCancelADifferentTimeOnSameDay() {
        let key = AppModel.smartAlarmOccurrenceKey(date(16, hour: 7), calendar: calendar)
        XCTAssertEqual(AppModel.nextSmartAlarmDate(minutes: 540, weekdays: [], skippedOccurrence: key,
                                                   from: date(16), calendar: calendar), date(16, hour: 9))
    }

    func testSkipIdentityFollowsLocalWakeDateWhenTimezoneChanges() throws {
        let key = AppModel.smartAlarmOccurrenceKey(date(16, hour: 7), calendar: calendar)
        var shifted = calendar
        shifted.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let now = try XCTUnwrap(shifted.date(from: DateComponents(year: 2026, month: 9, day: 16, hour: 6)))
        let expected = shifted.date(from: DateComponents(year: 2026, month: 9, day: 17, hour: 7))
        XCTAssertEqual(AppModel.nextSmartAlarmDate(minutes: 420, weekdays: [], skippedOccurrence: key,
                                                   from: now, calendar: shifted), expected)
    }

    @MainActor
    func testPlannerSettingsSurviveRestartWithoutEnablingAlarm() throws {
        let suite = "SleepPlannerAlarmTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = SleepPlannerSettings(defaults: defaults)
        settings.goalPercent = 85
        settings.goalOverrides = [7: 70]
        settings.alarmMode = "sleepGoal"
        settings.skippedOccurrence = "2026-09-16|420"
        let restored = SleepPlannerSettings(defaults: defaults)
        XCTAssertEqual(restored.goalPercent, 85)
        XCTAssertEqual(restored.goalOverrides, [7: 70])
        XCTAssertEqual(restored.alarmMode, "sleepGoal")
        XCTAssertEqual(restored.skippedOccurrence, "2026-09-16|420")
        XCTAssertFalse(defaults.bool(forKey: "behavior.smartAlarmEnabled"))
    }

    func testWakeInSpringGapPreservesRequestedMinuteAndSkipIdentity() throws {
        var local = calendar
        local.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let now = try XCTUnwrap(local.date(from: DateComponents(year: 2026, month: 3, day: 8, hour: 0)))
        let next = try XCTUnwrap(AppModel.nextSmartAlarmDate(minutes: 150, weekdays: [], from: now, calendar: local))
        XCTAssertEqual(local.component(.hour, from: next), 3)
        XCTAssertEqual(local.component(.minute, from: next), 30)
        XCTAssertEqual(next.timeIntervalSince(now), 150 * 60)
        XCTAssertEqual(AppModel.smartAlarmOccurrenceKey(next, calendar: local), "2026-03-08|210")
    }

    func testTimeOnlyControlPreservesConfiguredGapAndFoldMinutes() {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = SmartAlarmView.alarmClockTimeZone
        formatter.dateFormat = "HH:mm"
        XCTAssertEqual(formatter.string(from: SmartAlarmView.alarmClockDate(minutes: 150)), "02:30")
        XCTAssertEqual(formatter.string(from: SmartAlarmView.alarmClockDate(minutes: 90)), "01:30")
        for minute in 0..<1440 {
            XCTAssertEqual(SmartAlarmView.alarmClockMinutes(SmartAlarmView.alarmClockDate(minutes: minute)), minute)
        }
    }

    func testNotificationComponentsPreserveBothRepeatedHourInstants() throws {
        let formatter = ISO8601DateFormatter()
        for (instant, hour) in [("2026-11-01T05:30:00Z", 5), ("2026-11-01T06:30:00Z", 6)] {
            let date = try XCTUnwrap(formatter.date(from: instant))
            let parts = AppModel.sleepPlannerNotificationComponents(date)
            let calendar = try XCTUnwrap(parts.calendar)
            XCTAssertEqual(calendar.identifier, .gregorian)
            XCTAssertEqual(parts.timeZone?.secondsFromGMT(for: date), 0)
            XCTAssertEqual(parts.hour, hour)
            XCTAssertEqual(parts.minute, 30)
            XCTAssertEqual(calendar.date(from: parts), date)
        }
    }

    func testWakeInFallFoldUsesLaterOccurrence() throws {
        var local = calendar
        local.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let now = try XCTUnwrap(local.date(from: DateComponents(year: 2026, month: 11, day: 1, hour: 0)))
        let next = try XCTUnwrap(AppModel.nextSmartAlarmDate(minutes: 90, weekdays: [], from: now, calendar: local))
        XCTAssertEqual(local.component(.hour, from: next), 1)
        XCTAssertEqual(local.component(.minute, from: next), 30)
        XCTAssertEqual(next.timeIntervalSince(now), 150 * 60)
    }

    func testSkippedLaterFoldStaysPendingDuringEarlierHour() throws {
        var local = calendar
        local.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let formatter = ISO8601DateFormatter()
        let now = try XCTUnwrap(formatter.date(from: "2026-11-01T05:31:00Z"))
        let expired = try XCTUnwrap(formatter.date(from: "2026-11-01T06:31:00Z"))
        let nextWeek = try XCTUnwrap(formatter.date(from: "2026-11-08T06:30:00Z"))
        let key = "2026-11-01|90"
        XCTAssertTrue(PlannerAlarmPolicy.isSkipPending(skippedOccurrence: key, from: now, calendar: local))
        XCTAssertFalse(PlannerAlarmPolicy.isSkipPending(skippedOccurrence: key, from: expired, calendar: local))
        XCTAssertEqual(AppModel.nextSmartAlarmDate(minutes: 90, weekdays: [1], skippedOccurrence: key,
                                                   from: now, calendar: local), nextWeek)
    }
}
