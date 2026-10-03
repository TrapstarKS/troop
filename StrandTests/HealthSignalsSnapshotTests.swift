import XCTest
import StrandAnalytics
import WhoopStore
@testable import Strand

@MainActor
final class HealthSignalsSnapshotTests: XCTestCase {
    private func metric(day: String, sleep: Double? = nil, restingHr: Int? = nil,
                        hrv: Double? = nil, recovery: Double? = nil, respiration: Double? = nil,
                        oxygen: Double? = nil, skin: Double? = nil, steps: Int? = nil) -> DailyMetric {
        DailyMetric(day: day, totalSleepMin: sleep, efficiency: nil, deepMin: nil,
                    remMin: nil, lightMin: nil, disturbances: nil, restingHr: restingHr,
                    avgHrv: hrv, recovery: recovery, strain: nil, exerciseCount: nil,
                    spo2Pct: oxygen, skinTempDevC: skin, respRateBpm: respiration, steps: steps)
    }

    func testJoinedSnapshotCannotLendNewImportedProofToCachedComputedValues() async throws {
        let now = Date()
        let day = Repository.localDayKey(now)
        let keys = HealthSignalReliability.dayKeys(ending: day, count: 20)
        let store = try await WhoopStore.inMemory()
        let computed = keys.map {
            metric(day: $0, sleep: 420, restingHr: 50, hrv: 40, recovery: 70,
                   respiration: 16, oxygen: 98, skin: 33)
        }
        _ = try await store.upsertDailyMetrics(computed, deviceId: "my-whoop-noop")
        _ = try await store.upsertMetricSeries(keys.flatMap {
            [MetricPoint(day: $0, key: "hrv_fresh_scoring_valid", value: 0),
             MetricPoint(day: $0, key: "resp_fresh_scoring_valid", value: 0)]
        }, deviceId: "my-whoop-noop")
        let repo = Repository(deviceId: "my-whoop")
        repo.setStoreForTesting(store)
        await repo.refresh(days: 45)
        let requestedDays = repo.days
        let previewRows = repo.vitalMetricRows
        let captured = try await repo.signalReliabilityByDay(from: keys[0], to: day)
        XCTAssertEqual(captured.hrv[day]?.eligible, false)
        XCTAssertEqual(captured.resp[day]?.eligible, false)

        let imported = keys.map {
            metric(day: $0, sleep: 480, restingHr: 58, hrv: 40, recovery: 60,
                   respiration: 20, oxygen: 93, skin: 34)
        }
        _ = try await store.upsertDailyMetrics(imported, deviceId: "my-whoop")
        let snapshot = try await repo.signalReliabilityByDay(from: keys[0], to: day)

        XCTAssertEqual(repo.days, requestedDays, "the cache deliberately remains an older generation")
        XCTAssertEqual(repo.vitalMetricRows, previewRows)
        XCTAssertEqual(snapshot.hrv[day], .init(value: 40, eligible: true))
        XCTAssertEqual(snapshot.resp[day], .init(value: 20, eligible: true))
        XCTAssertEqual(snapshot.days.last, imported.last)
        let rows = HealthMonitorSnapshot.rows(sourceRows: snapshot.sourceRows, now: now, todayKey: day,
            hrvReliabilityByDay: snapshot.hrv, respReliabilityByDay: snapshot.resp,
            hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(rows.first { $0.id == "hrv" }?.reading.source, .whoopImport)
        XCTAssertEqual(rows.map { $0.reading.value }, [40, 58, 20, 93, 34])
        XCTAssertTrue(rows.allSatisfy { $0.assessment.status == .withinRange })
        XCTAssertEqual(HealthMonitorSnapshot.resolvedValues(key: "resp", sourceRows: snapshot.sourceRows,
            absoluteSkin: true, respReliabilityByDay: snapshot.resp)[day], 20)
        let preview = HealthMonitorSnapshot.rows(sourceRows: previewRows, now: now, todayKey: day,
            hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(preview.first { $0.id == "hrv" }?.assessment.status, .unverified)
        XCTAssertEqual(captured.days.last, computed.last, "an already captured snapshot remains immutable")
        XCTAssertEqual(captured.hrv[day]?.eligible, false)
        XCTAssertEqual(captured.resp[day]?.eligible, false)
    }

    func testJoinedSnapshotKeepsIndependentPhysicalOwnersAndRejectsRemovedRows() async throws {
        let day = Repository.localDayKey(Date())
        let store = try await WhoopStore.inMemory()
        let repo = Repository(deviceId: "active")
        repo.setStoreForTesting(store)
        _ = try await store.upsertDailyMetrics([metric(day: day, hrv: 60, respiration: 20)],
                                               deviceId: "my-whoop-noop")
        _ = try await store.upsertMetricSeries([
            MetricPoint(day: day, key: "hrv_fresh_scoring_valid", value: 1),
            MetricPoint(day: day, key: "resp_fresh_scoring_valid", value: 1)
        ], deviceId: "my-whoop-noop")
        _ = try await store.upsertMetricSeries([
            MetricPoint(day: day, key: "hrv_fresh_scoring_valid", value: 0),
            MetricPoint(day: day, key: "resp_fresh_scoring_valid", value: 1)
        ], deviceId: "active-noop")
        for activeHrv: Double? in [40, nil] {
            _ = try await store.upsertDailyMetrics([metric(day: day, hrv: activeHrv, respiration: 16)],
                                                   deviceId: "active-noop")
            let snapshot = try await repo.signalReliabilityByDay(from: day, to: day)
            XCTAssertEqual(snapshot.days.first?.avgHrv, activeHrv ?? 60)
            XCTAssertEqual(snapshot.days.first?.respRateBpm, 16)
            XCTAssertEqual(snapshot.hrv[day], .init(value: activeHrv ?? 60, eligible: activeHrv == nil))
            XCTAssertEqual(snapshot.resp[day], .init(value: 16, eligible: true))
            XCTAssertEqual(snapshot.sourceRows.first?.metric.avgHrv, activeHrv ?? 60)
            XCTAssertEqual(snapshot.sourceRows.first?.metric.respRateBpm, 16)
        }
        repo.days = [metric(day: "2000-01-01", restingHr: 50, hrv: 40, recovery: 70,
                            respiration: 16, oxygen: 98, skin: 33)]
        let removed = try await repo.signalReliabilityByDay(from: "2000-01-01", to: "2000-01-01")
        XCTAssertTrue(removed.days.isEmpty)
        XCTAssertTrue(removed.sourceRows.isEmpty)
        XCTAssertTrue(removed.hrv.isEmpty)
        XCTAssertTrue(removed.resp.isEmpty)
    }

    func testJoinedRowsKeepAppleOnlyVitalsEditedSleepAndActivityFileSteps() async throws {
        let now = Date()
        let day = Repository.localDayKey(now)
        let store = try await WhoopStore.inMemory()
        let repo = Repository(deviceId: "my-whoop")
        repo.setStoreForTesting(store)
        let end = Int(now.timeIntervalSince1970)
        repo.sleeps = [CachedSleepSession(startTs: end - 8 * 3_600, endTs: end,
            efficiency: nil, restingHr: nil, avgHrv: nil, stagesJSON: nil, userEdited: true)]
        _ = try await store.upsertDailyMetrics([metric(day: day, sleep: 480, recovery: 60)],
                                               deviceId: "my-whoop")
        _ = try await store.upsertDailyMetrics([metric(day: day, sleep: 321)], deviceId: "my-whoop-noop")
        let apple = metric(day: day, restingHr: 66, oxygen: 96)
        _ = try await store.upsertDailyMetrics([apple], deviceId: Repository.appleHealthSource)
        _ = try await store.upsertDailyMetrics([metric(day: day, steps: 12_345)],
                                               deviceId: Repository.activityFileSource)
        let snapshot = try await repo.signalReliabilityByDay(from: day, to: day)

        XCTAssertEqual(snapshot.days.first?.totalSleepMin, 321)
        _ = try await store.upsertDailyMetrics([metric(day: day, sleep: nil)], deviceId: "my-whoop-noop")
        let cleared = try await repo.signalReliabilityByDay(from: day, to: day)
        XCTAssertNil(cleared.days.first?.totalSleepMin, "the manual clear must not restore imported sleep")
        XCTAssertEqual(cleared.days.first?.recovery, 60)
        XCTAssertEqual(cleared.days.first?.steps, 12_345)
        XCTAssertEqual(snapshot.days.first?.totalSleepMin, 321, "the captured snapshot retains its edited sleep")
        XCTAssertEqual(snapshot.days.first?.steps, 12_345)
        XCTAssertEqual(snapshot.days.first?.recovery, 60)
        XCTAssertTrue(snapshot.sourceRows.contains { $0.source == .appleHealth && $0.metric == apple })
        XCTAssertTrue(snapshot.hrv.isEmpty)
        XCTAssertTrue(snapshot.resp.isEmpty)
        let rows = HealthMonitorSnapshot.rows(sourceRows: snapshot.sourceRows, now: now, todayKey: day,
            hrvReliabilityByDay: snapshot.hrv, respReliabilityByDay: snapshot.resp,
            hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(rows.first { $0.id == "rhr" }?.reading.value, 66)
        XCTAssertEqual(rows.first { $0.id == "rhr" }?.reading.source, .appleHealth)
        XCTAssertEqual(rows.first { $0.id == "spo2" }?.reading.value, 96)
    }
}
