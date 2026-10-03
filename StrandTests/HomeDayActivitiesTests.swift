import XCTest
import WhoopStore
@testable import Strand

@MainActor
final class HomeDayActivitiesTests: XCTestCase {
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
