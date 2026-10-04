import XCTest
import WhoopStore
@testable import Strand

final class ActiveEnergyReadTests: XCTestCase {
    private func row(total: Double? = 2345, active: Double? = nil) -> DailyMetric {
        DailyMetric(day: "2026-10-01", totalSleepMin: nil, efficiency: nil, deepMin: nil,
            remMin: nil, lightMin: nil, disturbances: nil, restingHr: nil, avgHrv: nil,
            recovery: 72, strain: nil, exerciseCount: nil,
            activeKcalEst: total, activeEnergyKcalEst: active)
    }

    func testActiveFacadeNeverFallsBackToLegacyTotal() {
        XCTAssertNil(Repository.dailyColumn(key: "active_kcal", day: row()))
        XCTAssertEqual(Repository.dailyColumn(key: "energy_kcal", day: row()), 2345)
        XCTAssertEqual(Repository.dailyColumn(key: "active_kcal", day: row(active: 456)), 456)
        XCTAssertEqual(Repository.dailyColumn(key: "active_kcal", day: row(active: 0)), 0)
    }

    func testCoalescingAndCopyingPreserveActiveIndependently() {
        let existing = row(active: 456)
        let merged = Repository.coalesceDay(row(total: 2000), existing)
        XCTAssertEqual(merged.activeKcalEst, 2000)
        XCTAssertEqual(merged.activeEnergyKcalEst, 456)
        XCTAssertEqual(merged.recovery, 72)
        XCTAssertEqual(existing.with(recovery: 73, skinTempDevC: nil, skinTempC: nil).activeEnergyKcalEst, 456)
        let zero = Repository.coalesceDay(row(active: 0), existing)
        XCTAssertEqual(zero.activeEnergyKcalEst, 0)
    }

    func testCaloriesDetailRoutesToTheSameActiveQuantity() {
        let local = MetricCatalog.todayCaloriesMetric(hasImportedKcal: false, hasOnDeviceKcal: true)
        XCTAssertEqual(local?.key, "active_kcal")
        XCTAssertEqual(local?.source, "my-whoop")
        XCTAssertEqual(MetricCatalog.todayCaloriesMetric(hasImportedKcal: true)?.source, "apple-health")
    }
}
