import XCTest
import WhoopStore
@testable import Strand

@MainActor
final class HomeDayActivitiesTests: XCTestCase {
    func testInvalidImportedScoresRemainUnavailable() {
        for value in [Double.nan, .infinity, -.infinity, -1, 101, 1e100] {
            XCTAssertNil(HomeScoreValue.resolve(value))
        }
        XCTAssertNil(HomeScoreValue.resolve(nil))
        for value in [0.0, 33.5, 34, 66.5, 67, 100] {
            XCTAssertEqual(HomeScoreValue.resolve(value), value)
        }
    }

    func testManualEntryKeepsTheDisplayedCivilDayAndNeverStartsInTheFuture() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let now = calendar.date(from: DateComponents(year: 2026, month: 10, day: 2, hour: 14, minute: 30))!
        let past = HomeDayActivities.manualEnd(dayKey: "2026-10-01", now: now, calendar: calendar)
        XCTAssertEqual(calendar.dateComponents([.year, .month, .day, .hour, .minute], from: past),
                       DateComponents(year: 2026, month: 10, day: 1, hour: 14, minute: 30))
        XCTAssertEqual(HomeDayActivities.manualEnd(dayKey: "2026-10-03", now: now, calendar: calendar), now)
        XCTAssertEqual(HomeDayActivities.manualEnd(dayKey: "invalid", now: now, calendar: calendar), now)
    }

    func testDayFeedUsesSelectedCivilDateAndKeepsChronologicalOrder() {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: today)!
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
        let morning = workout(start: today.addingTimeInterval(3_600))
        let afternoon = workout(start: today.addingTimeInterval(43_200))
        let rows = [afternoon, workout(start: tomorrow), morning, workout(start: yesterday)]

        XCTAssertEqual(HomeDayActivities.rows(rows, dayKey: Repository.localDayKey(today)).map(\.startTs),
                       [morning.startTs, afternoon.startTs])
        XCTAssertTrue(HomeDayActivities.rows(rows, dayKey: "1900-01-01").isEmpty)
    }

    func testPreFourBankedNightKeepsPostMidnightActivityAndDisplayedManualDate() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Sao_Paulo")!
        let now = calendar.date(from: DateComponents(year: 2026, month: 10, day: 3, hour: 2))!
        let midnightActivity = workout(start: now.addingTimeInterval(-1_800))
        let previousActivity = workout(start: now.addingTimeInterval(-10_800))
        let days = ["2026-10-02", "2026-10-03"].map {
            DailyMetric(day: $0, totalSleepMin: 430, efficiency: nil, deepMin: nil, remMin: nil,
                        lightMin: nil, disturbances: nil, restingHr: nil, avgHrv: nil,
                        recovery: 50, strain: 40, exerciseCount: nil)
        }
        let displayedDay = Repository.resolveToday(days: days, logicalKey: "2026-10-02", localKey: "2026-10-03")!.day
        XCTAssertEqual(displayedDay, "2026-10-03")
        XCTAssertEqual(HomeDayActivities.rows([previousActivity, midnightActivity], dayKey: displayedDay,
                                             calendar: calendar).map(\.startTs), [midnightActivity.startTs])
        XCTAssertEqual(HomeDayActivities.manualEnd(dayKey: displayedDay, now: now, calendar: calendar), now)
        XCTAssertNotEqual(HomeDayActivities.manualEnd(dayKey: "2026-10-02", now: now, calendar: calendar), now)
    }

    func testDashboardVitalRoutesResolveRealCatalogKeys() {
        for metric in [KeyMetric.hrv, .restingHr, .bloodOxygen, .respiratory, .skinTemp] {
            guard case .metric(let key) = HomeMetricRoute.route(metric) else {
                return XCTFail("Expected metric route")
            }
            XCTAssertNotNil(MetricCatalog.all.first { $0.key == key })
        }
    }

    private func workout(start: Date) -> WorkoutRow {
        WorkoutRow(startTs: Int(start.timeIntervalSince1970),
                   endTs: Int(start.addingTimeInterval(1_800).timeIntervalSince1970),
                   sport: "Running", source: "manual", durationS: nil, energyKcal: nil,
                   avgHr: nil, maxHr: nil, strain: nil, distanceM: nil,
                   zonesJSON: nil, notes: nil, steps: nil)
    }
}
