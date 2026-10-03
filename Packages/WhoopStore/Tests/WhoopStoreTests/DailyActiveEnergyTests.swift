import XCTest
import GRDB
@testable import WhoopStore

final class DailyActiveEnergyTests: XCTestCase {
    private func row(active: Double? = nil) -> DailyMetric {
        DailyMetric(day: "2026-10-01", totalSleepMin: 420, efficiency: 0.9, deepMin: 80,
                    remMin: 100, lightMin: 240, disturbances: 1, restingHr: 52, avgHrv: 68,
                    recovery: 72, strain: 35, exerciseCount: 1, steps: 4321,
                    activeKcalEst: 2345, activeEnergyKcalEst: active)
    }

    func testMigrationPreservesLegacyTotalAndLeavesActiveUnknown() throws {
        let database = try DatabaseQueue()
        let migrator = WhoopStore.makeMigrator()
        try migrator.migrate(database, upTo: "v47-rr-whoop5-fill")
        try database.write { db in
            try db.execute(sql: "INSERT INTO dailyMetric (deviceId, day, activeKcalEst, recovery) VALUES ('my-whoop-noop', '2026-10-01', 2345, 72)")
        }
        try migrator.migrate(database)
        try database.read { db in
            let row = try XCTUnwrap(Row.fetchOne(db, sql: "SELECT activeKcalEst, activeEnergyKcalEst, recovery FROM dailyMetric"))
            XCTAssertEqual(row["activeKcalEst"] as Double?, 2345)
            XCTAssertNil(row["activeEnergyKcalEst"] as Double?)
            XCTAssertEqual(row["recovery"] as Double?, 72)
            let column = try XCTUnwrap(db.columns(in: "dailyMetric").first { $0.name == "activeEnergyKcalEst" })
            XCTAssertFalse(column.isNotNull)
            XCTAssertNil(column.defaultValueSQL)
            XCTAssertEqual(try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM dailyMetric"), 1)
        }
    }

    func testActiveAndTotalRoundTripAndUpdateIndependently() async throws {
        let store = try await WhoopStore.inMemory()
        _ = try await store.upsertDailyMetrics([row(active: 456)], deviceId: "my-whoop-noop")
        var stored = try await store.dailyMetrics(deviceId: "my-whoop-noop", from: "2026-10-01", to: "2026-10-01")
        XCTAssertEqual(stored, [row(active: 456)])
        _ = try await store.upsertDailyMetrics([row(active: 567)], deviceId: "my-whoop-noop")
        stored = try await store.dailyMetrics(deviceId: "my-whoop-noop", from: "2026-10-01", to: "2026-10-01")
        XCTAssertEqual(stored, [row(active: 567)])
        XCTAssertEqual(stored.first?.activeKcalEst, 2345)
        XCTAssertEqual(stored.first?.recovery, 72)
    }

    func testOldCodableRowsDoNotInventActiveFromTotal() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(row(active: 456))) as? [String: Any])
        object.removeValue(forKey: "activeEnergyKcalEst")
        let legacy = try JSONDecoder().decode(DailyMetric.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(legacy, row())
    }
}
