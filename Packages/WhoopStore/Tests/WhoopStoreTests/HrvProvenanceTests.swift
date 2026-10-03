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
        XCTAssertEqual(rows.first?.respValue, 16)
        XCTAssertEqual(rows.first?.respFreshScoringValid, 1)
    }

    func testJoinedReadCannotMixAnUncommittedDailyAndMarkerGeneration() async throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite").path
        defer { for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) } }
        let store = try await WhoopStore(path: path)
        try await store.upsertDevice(id: "active-noop", mac: nil, name: nil)
        try await store.registryWriter.write { db in
            try db.execute(sql: "INSERT INTO dailyMetric(deviceId, day, avgHrv, respRateBpm) VALUES ('active-noop', '2026-06-10', 40, 16)")
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('active-noop', '2026-06-10', 'hrv_fresh_scoring_valid', 1)")
            try db.execute(sql: "INSERT INTO metricSeries VALUES ('active-noop', '2026-06-10', 'resp_fresh_scoring_valid', 1)")
        }
        let started = DispatchSemaphore(value: 0)
        let finish = DispatchSemaphore(value: 0)
        let writer = Task.detached {
            try await store.registryWriter.write { db in
                try db.execute(sql: "UPDATE dailyMetric SET avgHrv = 20, respRateBpm = 20 WHERE deviceId = 'active-noop'")
                started.signal()
                guard finish.wait(timeout: .now() + 10) == .success else { throw CancellationError() }
                try db.execute(sql: "UPDATE metricSeries SET value = 0 WHERE deviceId = 'active-noop'")
            }
        }
        defer { finish.signal() }
        XCTAssertEqual(started.wait(timeout: .now() + 10), .success)
        let before = try await store.hrvProvenance(deviceIds: ["active-noop"], from: "2026-06-10", to: "2026-06-10")
        XCTAssertEqual(before.first?.value, 40)
        XCTAssertEqual(before.first?.freshScoringValid, 1)
        XCTAssertEqual(before.first?.respValue, 16)
        XCTAssertEqual(before.first?.respFreshScoringValid, 1)
        finish.signal()
        try await writer.value
        let after = try await store.hrvProvenance(deviceIds: ["active-noop"], from: "2026-06-10", to: "2026-06-10")
        XCTAssertEqual(after.first?.value, 20)
        XCTAssertEqual(after.first?.freshScoringValid, 0)
        XCTAssertEqual(after.first?.respValue, 20)
        XCTAssertEqual(after.first?.respFreshScoringValid, 0)
    }
}
