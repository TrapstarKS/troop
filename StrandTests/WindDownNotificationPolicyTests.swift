import Foundation
import UserNotifications
import XCTest
@testable import Strand

@MainActor
final class WindDownNotificationPolicyTests: XCTestCase {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }

    private func date(hour: Int, minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 10, day: 2, hour: hour, minute: minute))!
    }

    private func fixture(_ defaults: UserDefaults) {
        defaults.set(true, forKey: "windDown.enabled")
        defaults.set(420, forKey: "behavior.smartAlarmMinutes")
        defaults.set(30, forKey: "windDown.leadMinutes")
        defaults.set(480, forKey: "sleepPlanner.baseNeedMinutes")
        defaults.set(3, forKey: "sleepPlanner.historyNights")
    }

    func testEachQuietSettingReplacesAdviceWithoutCancellingWakeAlarms() async throws {
        let suite = "WindDownNotificationPolicyTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        fixture(defaults)
        let planner = SleepPlannerSettings(defaults: defaults)
        let center = TestCenter()
        let wake = UNNotificationRequest(identifier: "smart-alarm-backup-occurrence1",
                                         content: UNMutableNotificationContent(), trigger: nil)
        center.requests[wake.identifier] = wake
        var refresh: Task<Void, Never>?
        let store = NotificationSettingsStore(defaults: defaults, rescheduleAdvice: {
            refresh = WindDownNudge.reschedule(from: self.date(hour: 22), defaults: defaults,
                                               planner: planner, calendar: self.calendar, center: center.adapter)
        })
        await WindDownNudge.reschedule(from: date(hour: 22), defaults: defaults,
                                      planner: planner, calendar: calendar, center: center.adapter).value
        XCTAssertEqual(center.advice.count, 7)
        store.quietHoursEnabled = true
        await refresh?.value
        XCTAssertEqual(center.advice.count, 0)
        XCTAssertNotNil(center.requests[wake.identifier])

        store.quietStartMinutes = 1380
        await refresh?.value
        XCTAssertEqual(center.advice.count, 7)
        store.quietStartMinutes = 1320
        await refresh?.value
        XCTAssertEqual(center.advice.count, 0)
        XCTAssertNotNil(center.requests[wake.identifier])

        store.quietStartMinutes = 1200
        store.quietEndMinutes = 1200
        await refresh?.value
        XCTAssertEqual(center.advice.count, 7)
        store.quietEndMinutes = 1380
        await refresh?.value
        XCTAssertEqual(center.advice.count, 0)
        XCTAssertEqual(center.requests.keys.sorted(), [wake.identifier])
    }

    func testDeliveredOrElapsedAdviceStaysHandledThroughGoalDebtEditsAndRestart() async throws {
        for deliveredVisible in [true, false] {
            let suite = "WindDownNotificationPolicyTests.\(UUID().uuidString)"
            let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
            defer { defaults.removePersistentDomain(forName: suite) }
            fixture(defaults)
            let center = TestCenter()
            let planner = SleepPlannerSettings(defaults: defaults)
            await WindDownNudge.reschedule(from: date(hour: 22), defaults: defaults,
                                          planner: planner, calendar: calendar, center: center.adapter).value
            let key = "2026-10-03|420"
            let first = try XCTUnwrap(center.advice.first { $0.content.userInfo[WindDownNudge.occurrenceInfoKey] as? String == key })
            if deliveredVisible { center.deliveredRequests = [first] }
            center.requests.removeValue(forKey: first.identifier)
            planner.goalPercent = 85
            await WindDownNudge.reschedule(from: date(hour: 22, minute: 40), defaults: defaults,
                                          planner: planner, calendar: calendar, center: center.adapter).value
            XCTAssertFalse(center.advice.contains { $0.content.userInfo[WindDownNudge.occurrenceInfoKey] as? String == key })
            XCTAssertEqual(defaults.string(forKey: "windDown.lastDeliveredOccurrence"), deliveredVisible ? key : nil)
            center.deliveredRequests = []
            let restored = SleepPlannerSettings(defaults: defaults)
            restored.goalPercent = 70
            restored.debtReminderEnabled = true
            defaults.set(120, forKey: "sleepPlanner.debtMinutes")
            let withDebt = SleepPlannerSettings(defaults: defaults)
            for _ in 0..<3 {
                await WindDownNudge.reschedule(from: date(hour: 22, minute: 40), defaults: defaults,
                                              planner: withDebt, calendar: calendar, center: center.adapter).value
                XCTAssertFalse(center.advice.contains { $0.content.userInfo[WindDownNudge.occurrenceInfoKey] as? String == key })
                XCTAssertTrue(center.advice.contains { $0.content.userInfo[WindDownNudge.occurrenceInfoKey] as? String == "2026-10-04|420" })
                XCTAssertEqual(center.advice.count, 7)
            }
        }
    }

    @MainActor
    private final class TestCenter {
        var requests: [String: UNNotificationRequest] = [:]
        var deliveredRequests: [UNNotificationRequest] = []
        var advice: [UNNotificationRequest] {
            requests.values.filter { $0.identifier.hasPrefix("wind-down-nudge-") }
        }
        var adapter: WindDownNudge.AdviceNotificationCenter {
            .init(pending: { Array(self.requests.values) }, delivered: { self.deliveredRequests },
                  removePending: { ids in for id in ids { self.requests.removeValue(forKey: id) } },
                  add: { self.requests[$0.identifier] = $0 })
        }
    }
}
