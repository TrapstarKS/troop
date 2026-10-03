import XCTest
import GRDB
@testable import WhoopStore

final class HrvProvenanceTests: XCTestCase {
    func testMarkersJoinOnlyTheirPhysicalSourceAndDay() async throws {
        let store = try await WhoopStore.inMemory()
        for id in ["active-noop", "my-whoop-noop", "unrelated-noop"] {
            try await store.upsertDevice(id: id, mac: nil, name: nil)
        }
        try await store.registryWriter.write { db in
            for id in ["active-noop", "my-whoop-noop", "unrelated-noop"] {
                try db.execute(sql: "INSERT INTO dailyMetric(deviceId, day, avgHrv, respRateBpm) VALUES (?, '2026-06-10', 40, 16)", arguments: [id])
            }
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('my-whoop-noop', '2026-06-10', 'hrv_fresh_scoring_valid', 1)")
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('active-noop', '2026-06-09', 'hrv_fresh_scoring_valid', 1)")
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('my-whoop-noop', '2026-06-10', 'hrv_rr_overcount', 0)")
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('my-whoop-noop', '2026-06-10', 'resp_fresh_scoring_valid', 1)")
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('active-noop', '2026-06-09', 'resp_fresh_scoring_valid', 1)")
        }
        let rows = try await store.hrvProvenance(deviceIds: ["active-noop", "my-whoop-noop"], from: "2026-06-10", to: "2026-06-10")
        XCTAssertEqual(rows.count, 2)
        let active = try XCTUnwrap(rows.first { $0.deviceId == "active-noop" })
        XCTAssertEqual(active.metric?.day, active.day)
        XCTAssertEqual(active.metric?.avgHrv, active.value)
        XCTAssertEqual(active.metric?.respRateBpm, active.respValue)
        XCTAssertNil(active.freshScoringValid)
        XCTAssertNil(active.overcount)
        XCTAssertEqual(active.respValue, 16)
        XCTAssertNil(active.respFreshScoringValid)
        XCTAssertEqual(rows.first { $0.deviceId == "my-whoop-noop" }?.respFreshScoringValid, 1)
        XCTAssertEqual(rows.first { $0.deviceId == "my-whoop-noop" }?.freshScoringValid, 1)
        let empty = try await store.hrvProvenance(deviceIds: [], from: "2026-06-10", to: "2026-06-10")
        XCTAssertTrue(empty.isEmpty)
    }

    func testVendorRespirationSurvivesWithoutAnyHrvRowOrMarker() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.upsertDevice(id: "oura-ring-noop", mac: nil, name: nil)
        try await store.registryWriter.write { db in
            try db.execute(sql: "INSERT INTO dailyMetric(deviceId, day, respRateBpm) VALUES ('oura-ring-noop', '2026-06-10', 16)")
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('oura-ring-noop', '2026-06-10', 'resp_fresh_scoring_valid', 1)")
        }
        let rows = try await store.hrvProvenance(deviceIds: ["oura-ring-noop"], from: "2026-06-10", to: "2026-06-10")
        XCTAssertEqual(rows.count, 1)
        XCTAssertNil(rows.first?.value)
        XCTAssertNil(rows.first?.freshScoringValid)
        XCTAssertEqual(rows.first?.metric?.respRateBpm, 16)
        XCTAssertEqual(rows.first?.respValue, 16)
        XCTAssertEqual(rows.first?.respFreshScoringValid, 1)
    }

    func testEveryPhysicalDailyRowSurvivesWithoutHrvOrRespiration() async throws {
        let store = try await WhoopStore.inMemory()
        for id in ["active-noop", "my-whoop-noop"] {
            try await store.upsertDevice(id: id, mac: nil, name: nil)
        }
        let columns = ["avgHrv", "restingHr", "respRateBpm", "spo2Pct", "skinTempDevC", "skinTempC", "recovery", "totalSleepMin"]
        let values: [Double] = [40, 60, 16, 98, 0.4, 33.5, 75, 480]
        let days = (1...9).map { String(format: "2026-06-%02d", $0) }
        try await store.registryWriter.write { db in
            for (index, column) in columns.enumerated() {
                try db.execute(sql: "INSERT INTO dailyMetric(deviceId, day, \(column)) VALUES ('active-noop', ?, ?)",
                               arguments: [days[index], values[index]])
            }
            try db.execute(sql: "INSERT INTO dailyMetric(deviceId, day) VALUES ('active-noop', '2026-06-09')")
            try db.execute(sql: "INSERT INTO dailyMetric(deviceId, day, recovery) VALUES ('active-noop', '2026-05-31', 75), ('active-noop', '2026-06-10', 75), ('my-whoop-noop', '2026-06-01', 75)")
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('active-noop', '2026-06-08', 'hrv_fresh_scoring_valid', 1), ('my-whoop-noop', '2026-06-01', 'hrv_fresh_scoring_valid', 1), ('active-noop', '2026-06-12', 'hrv_fresh_scoring_valid', 1)")
        }
        let rows = try await store.hrvProvenance(deviceIds: ["active-noop"], from: days[0], to: days[8])
        XCTAssertEqual(rows.map(\.day), days)
        XCTAssertTrue(rows.allSatisfy { $0.deviceId == "active-noop" })
        let metrics = try rows.map { try XCTUnwrap($0.metric) }
        XCTAssertEqual(metrics.map(\.day), days)
        XCTAssertEqual(metrics.map(\.avgHrv), [40, nil, nil, nil, nil, nil, nil, nil, nil])
        XCTAssertEqual(metrics.map(\.restingHr), [nil, 60, nil, nil, nil, nil, nil, nil, nil])
        XCTAssertEqual(metrics.map(\.respRateBpm), [nil, nil, 16, nil, nil, nil, nil, nil, nil])
        XCTAssertEqual(metrics.map(\.spo2Pct), [nil, nil, nil, 98, nil, nil, nil, nil, nil])
        XCTAssertEqual(metrics.map(\.skinTempDevC), [nil, nil, nil, nil, 0.4, nil, nil, nil, nil])
        XCTAssertEqual(metrics.map(\.skinTempC), [nil, nil, nil, nil, nil, 33.5, nil, nil, nil])
        XCTAssertEqual(metrics.map(\.recovery), [nil, nil, nil, nil, nil, nil, 75, nil, nil])
        XCTAssertEqual(metrics.map(\.totalSleepMin), [nil, nil, nil, nil, nil, nil, nil, 480, nil])
        for row in rows {
            XCTAssertEqual(row.value, row.metric?.avgHrv)
            XCTAssertEqual(row.respValue, row.metric?.respRateBpm)
            XCTAssertNil(row.overcount)
            XCTAssertNil(row.respFreshScoringValid)
            XCTAssertEqual(row.freshScoringValid, row.day == "2026-06-08" ? 1 : nil)
        }
    }

    func testJoinedReadCannotMixAnUncommittedDailyAndMarkerGeneration() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        let store = try await WhoopStore(path: path)
        try await store.upsertDevice(id: "active-noop", mac: nil, name: nil)
        let oldMetric = DailyMetric(day: "2026-06-10", totalSleepMin: 480, efficiency: 91, deepMin: 80,
                                    remMin: 110, lightMin: 290, disturbances: 3, restingHr: 60,
                                    avgHrv: 40, recovery: 75, strain: 7.5, exerciseCount: 2,
                                    spo2Pct: 98, skinTempDevC: 0.4, respRateBpm: 16,
                                    steps: 12345, activeKcalEst: 2300, activeEnergyKcalEst: 700,
                                    spo2Red: 501, spo2Ir: 601, avgSdnn: 45, skinTempC: 33.5,
                                    sleepHrOnly: true)
        let newMetric = DailyMetric(day: "2026-06-10", totalSleepMin: 360, efficiency: 75, deepMin: 40,
                                    remMin: 80, lightMin: 240, disturbances: 5, restingHr: 80,
                                    avgHrv: 20, recovery: 30, strain: 12, exerciseCount: 1,
                                    spo2Pct: 94, skinTempDevC: 1.2, respRateBpm: 20,
                                    steps: 4567, activeKcalEst: 1900, activeEnergyKcalEst: 500,
                                    spo2Red: 503, spo2Ir: 603, avgSdnn: 25, skinTempC: 35,
                                    sleepHrOnly: false)
        try await store.registryWriter.write { db in
            _ = try WhoopStore.upsertDailyMetrics([oldMetric], deviceId: "active-noop", in: db)
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('active-noop', '2026-06-10', 'hrv_fresh_scoring_valid', 1)")
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('active-noop', '2026-06-10', 'hrv_rr_overcount', 0)")
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('active-noop', '2026-06-10', 'resp_fresh_scoring_valid', 1)")
        }
        let started = DispatchSemaphore(value: 0)
        let finish = DispatchSemaphore(value: 0)
        let writer = Task.detached {
            try await store.registryWriter.write { db in
                _ = try WhoopStore.upsertDailyMetrics([newMetric], deviceId: "active-noop", in: db)
                started.signal()
                guard finish.wait(timeout: .now() + 10) == .success else { throw CancellationError() }
                try db.execute(sql: "UPDATE metricSeries SET value = CASE WHEN key = 'hrv_rr_overcount' THEN 1 ELSE 0 END WHERE deviceId = 'active-noop'")
            }
        }
        defer { finish.signal() }
        XCTAssertEqual(started.wait(timeout: .now() + 10), .success)
        let before = try await store.hrvProvenance(deviceIds: ["active-noop"], from: "2026-06-10", to: "2026-06-10")
        XCTAssertEqual(before.first?.metric, oldMetric)
        XCTAssertEqual(before.first?.value, 40)
        XCTAssertEqual(before.first?.freshScoringValid, 1)
        XCTAssertEqual(before.first?.overcount, 0)
        XCTAssertEqual(before.first?.respValue, 16)
        XCTAssertEqual(before.first?.respFreshScoringValid, 1)
        finish.signal()
        try await writer.value
        let after = try await store.hrvProvenance(deviceIds: ["active-noop"], from: "2026-06-10", to: "2026-06-10")
        XCTAssertEqual(after.first?.metric, newMetric)
        XCTAssertEqual(after.first?.value, 20)
        XCTAssertEqual(after.first?.freshScoringValid, 0)
        XCTAssertEqual(after.first?.overcount, 1)
        XCTAssertEqual(after.first?.respValue, 20)
        XCTAssertEqual(after.first?.respFreshScoringValid, 0)
    }
}
