import XCTest
import WhoopStore
import StrandImport
@testable import Strand

final class WhoopImportSkinTempTests: XCTestCase {
    private func row(_ day: String, deviation: Double? = nil, celsius: Double? = nil) -> DailyMetric {
        DailyMetric(day: day, totalSleepMin: 420, efficiency: 0.9, deepMin: 80, remMin: 100,
                    lightMin: 240, disturbances: 1, restingHr: 52, avgHrv: 68, recovery: 72,
                    strain: 35, exerciseCount: 1, spo2Pct: 97, skinTempDevC: deviation,
                    respRateBpm: 14, steps: 4321, activeKcalEst: 456, spo2Red: 123,
                    spo2Ir: 234, avgSdnn: 45, skinTempC: celsius, sleepHrOnly: true)
    }

    private func nights(_ temperatures: [Double]) -> [DailyMetric] {
        temperatures.enumerated().map { row(String(format: "2026-07-%02d", $0.offset + 1), celsius: $0.element) }
    }

    func testDeviationUsesOnlyPriorNightsAndPreservesMissingAbsolute() {
        let source = nights(Array(repeating: 33.5, count: 4) + [34.3])
        let output = WhoopImporter.withSkinTempDeviations(Array(source.reversed()))
        XCTAssertTrue(output.prefix(4).allSatisfy { $0.skinTempDevC == nil })
        XCTAssertEqual(output.last?.skinTempC, 34.3)
        XCTAssertEqual(output.last?.skinTempDevC, 0.8)
        let deviationOnly = row("2026-07-06", deviation: 0.2)
        XCTAssertEqual(WhoopImporter.withSkinTempDeviations([deviationOnly]), [deviationOnly])
    }

    func testIncrementalAndRepeatedImportUseStoredPriorHistoryOnce() async throws {
        let source = nights(Array(repeating: 33.5, count: 4) + [34.3])
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("physiological_cycles.csv")
        let incremental = try await WhoopStore.inMemory()
        let full = try await WhoopStore.inMemory()
        func write(_ rows: [DailyMetric]) throws {
            try WhoopCsvExporter.cyclesCSV(days: rows, series: [:]).write(to: url, atomically: true, encoding: .utf8)
        }
        try write(Array(source.prefix(4)))
        _ = try await WhoopImporter.importExport(url: directory, into: incremental, deviceId: "my-whoop")
        try write([source.last!])
        _ = try await WhoopImporter.importExport(url: directory, into: incremental, deviceId: "my-whoop")
        let once = try await incremental.dailyMetrics(deviceId: "my-whoop", from: "0000-01-01", to: "9999-12-31")
        _ = try await WhoopImporter.importExport(url: directory, into: incremental, deviceId: "my-whoop")
        let repeated = try await incremental.dailyMetrics(deviceId: "my-whoop", from: "0000-01-01", to: "9999-12-31")
        try write(source)
        _ = try await WhoopImporter.importExport(url: directory, into: full, deviceId: "my-whoop")
        let fullRows = try await full.dailyMetrics(deviceId: "my-whoop", from: "0000-01-01", to: "9999-12-31")
        XCTAssertEqual(once, repeated)
        XCTAssertEqual(once, fullRows)
        XCTAssertEqual(once.last?.skinTempDevC, 0.8)
        XCTAssertEqual(WhoopImporter.importerVersion, 2)

        let backward = try await WhoopStore.inMemory()
        let unrelated = row("2026-07-06", deviation: -0.42, celsius: 33.7)
        _ = try await backward.upsertDailyMetrics([unrelated], deviceId: "my-whoop")
        try write([source.last!])
        _ = try await WhoopImporter.importExport(url: directory, into: backward, deviceId: "my-whoop")
        try write(Array(source.prefix(4)))
        _ = try await WhoopImporter.importExport(url: directory, into: backward, deviceId: "my-whoop")
        let backwardRows = try await backward.dailyMetrics(deviceId: "my-whoop", from: "0000-01-01", to: "9999-12-31")
        XCTAssertEqual(Array(backwardRows.prefix(5)), fullRows)
        XCTAssertEqual(backwardRows.last, unrelated)
        _ = try await WhoopImporter.importExport(url: directory, into: backward, deviceId: "my-whoop")
        let backwardRepeated = try await backward.dailyMetrics(deviceId: "my-whoop", from: "0000-01-01", to: "9999-12-31")
        XCTAssertEqual(backwardRepeated, backwardRows)
    }

    func testDuplicateDaysUseLastIncomingRowAndDoNotDoubleSeed() {
        let source = nights(Array(repeating: 33.5, count: 4) + [34.3])
        let duplicate = row("2026-07-01", celsius: 36)
        XCTAssertEqual(WhoopImporter.withSkinTempDeviations([duplicate] + source), WhoopImporter.withSkinTempDeviations(source))
        XCTAssertEqual(WhoopImporter.withSkinTempDeviations(source, history: source), WhoopImporter.withSkinTempDeviations(source))
    }

    func testRepairChangesOnlyLegacyCandidatesInCanonicalImportNamespace() async throws {
        let store = try await WhoopStore.inMemory()
        let suite = "WhoopImportSkinTempTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let legacy = nights(Array(repeating: 33.5, count: 4) + [34.3]).map {
            $0.with(recovery: $0.recovery, skinTempDevC: $0.skinTempC, skinTempC: nil)
        }
        let existing = row("2026-07-06", deviation: -0.42, celsius: 33.7)
        let deviationOnly = row("2026-07-07", deviation: 0.2)
        let noMarker = row("2026-07-08", deviation: 34.2)
        let mismatched = row("2026-07-09", deviation: 34.2)
        let points = legacy.map { MetricPoint(day: $0.day, key: "skin_temp", value: $0.skinTempDevC!) }
            + [MetricPoint(day: mismatched.day, key: "skin_temp", value: 34.1)]
        _ = try await store.upsertDailyMetrics(legacy + [existing, deviationOnly, noMarker, mismatched], deviceId: "my-whoop")
        _ = try await store.upsertMetricSeries(points, deviceId: "my-whoop")
        _ = try await store.upsertDailyMetrics(legacy, deviceId: "registered-strap")
        _ = try await store.upsertDailyMetrics(legacy, deviceId: "my-whoop-noop")
        let skipped = await WhoopImporter.repairAbsoluteSkinTempIfNeeded(store: store, deviceId: "registered-strap", defaults: defaults)
        XCTAssertFalse(skipped)
        XCTAssertFalse(defaults.bool(forKey: WhoopImporter.skinTempRepairFlagKey))
        let changed = await WhoopImporter.repairAbsoluteSkinTempIfNeeded(store: store, deviceId: "my-whoop", defaults: defaults)
        XCTAssertTrue(changed)
        let repaired = try await store.dailyMetrics(deviceId: "my-whoop", from: "0000-01-01", to: "9999-12-31")
        XCTAssertEqual(Array(repaired.prefix(5)), WhoopImporter.withSkinTempDeviations(nights(Array(repeating: 33.5, count: 4) + [34.3])))
        XCTAssertEqual(Array(repaired.suffix(4)), [existing, deviationOnly, noMarker, mismatched])
        for device in ["registered-strap", "my-whoop-noop"] {
            let untouched = try await store.dailyMetrics(deviceId: device, from: "0000-01-01", to: "9999-12-31")
            XCTAssertEqual(untouched, legacy)
        }
        let again = await WhoopImporter.repairAbsoluteSkinTempIfNeeded(store: store, deviceId: "my-whoop", defaults: defaults)
        XCTAssertFalse(again)
        XCTAssertTrue(WhoopImporter.skinTempRepair(repaired, importedTemperatures: points).isEmpty)
        XCTAssertTrue(WhoopImporter.skinTempRepair(legacy, importedTemperatures: []).isEmpty)
    }
}
