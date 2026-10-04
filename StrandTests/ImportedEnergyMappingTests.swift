import XCTest
import Foundation
import SQLite3
import WhoopStore
@testable import Strand

final class ImportedEnergyMappingTests: XCTestCase {
    func testWearableImportsKnownTotalAndActiveAsDistinctQuantities() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("oura-energy-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        try Data(#"{"daily_activity":[{"day":"2026-06-01","active_calories":520,"total_calories":2450},{"day":"2026-06-02","active_calories":312}]}"#.utf8).write(to: url)
        let store = try await WhoopStore.inMemory()
        _ = try await WearableImporter.importExport(url: url, into: store)
        let days = try await store.dailyMetrics(deviceId: "oura-import", from: "2026-06-01", to: "2026-06-02")
        XCTAssertEqual(days.map(\.activeEnergyKcalEst), [520, 312])
        XCTAssertEqual(days.map(\.activeKcalEst), [2450, nil])
        let active = try await store.metricSeries(deviceId: "oura-import", key: "active_kcal", from: "2026-06-01", to: "2026-06-02")
        let total = try await store.metricSeries(deviceId: "oura-import", key: "energy_kcal", from: "2026-06-01", to: "2026-06-02")
        XCTAssertEqual(active.map(\.value), [520, 312])
        XCTAssertEqual(total.map(\.value), [2450])
    }

    func testXiaomiActiveOnlyImportLeavesTotalUnknown() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("xiaomi-energy-\(UUID().uuidString).db")
        defer { try? FileManager.default.removeItem(at: url) }
        var database: OpaquePointer?
        guard sqlite3_open(url.path, &database) == SQLITE_OK else {
            return XCTFail("Cannot open the Xiaomi SQLite fixture")
        }
        defer { sqlite3_close(database) }
        let fixtureSQL = """
        BEGIN;
        CREATE TABLE steps (sid TEXT, key TEXT, time INTEGER, value TEXT, zone_offset INTEGER, time_zero INTEGER, deleted INTEGER DEFAULT 0);
        CREATE TABLE calories_day (sid TEXT, key TEXT, time INTEGER, value TEXT, zone_offset INTEGER, time_zero INTEGER, deleted INTEGER DEFAULT 0);
        INSERT INTO calories_day VALUES ('default','calories_day',1742601600,'{"calories":312}',0,1742601600,0);
        COMMIT;
        """
        guard sqlite3_exec(database, fixtureSQL, nil, nil, nil) == SQLITE_OK else {
            return XCTFail("Cannot populate the Xiaomi SQLite fixture")
        }
        let store = try await WhoopStore.inMemory()
        _ = try await XiaomiImporter.importExport(url: url, into: store)
        let rows = try await store.dailyMetrics(deviceId: "xiaomi-band", from: "2025-03-22", to: "2025-03-22")
        XCTAssertEqual(rows.first?.activeEnergyKcalEst, 312)
        XCTAssertNil(rows.first?.activeKcalEst)
        let total = try await store.metricSeries(deviceId: "xiaomi-band", key: "energy_kcal", from: "2025-03-22", to: "2025-03-22")
        XCTAssertTrue(total.isEmpty)
    }
}
