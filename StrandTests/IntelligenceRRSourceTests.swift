import XCTest
import Foundation
import WhoopProtocol
import WhoopStore
@testable import StrandAnalytics
@testable import Strand

@MainActor
final class IntelligenceRRSourceTests: XCTestCase {
    private let canonical = "my-whoop"
    private let active = "new-five"

    private func chargeDay(_ day: String, hrv: Double?) -> DailyMetric {
        DailyMetric(day: day, totalSleepMin: 480, efficiency: 0.9, deepMin: 90, remMin: 90,
            lightMin: 300, disturbances: 2, restingHr: 60, avgHrv: hrv, recovery: 72,
            strain: 61, exerciseCount: 2, spo2Pct: 98, skinTempDevC: 0.2, respRateBpm: 14,
            steps: 42, activeKcalEst: 1840, activeEnergyKcalEst: 420,
            spo2Red: 10, spo2Ir: 20, avgSdnn: 40, skinTempC: 34.2, sleepHrOnly: true)
    }

    func testChargeFreshnessBelongsToTheRawWinningSource() async throws {
        let store = try await WhoopStore.inMemory()
        let repo = Repository(deviceId: active)
        repo.setStoreForTesting(store)
        let day = "2026-10-03"
        let activeRow = chargeDay(day, hrv: 44)
        _ = try await store.upsertDailyMetrics([activeRow], deviceId: active + "-noop")
        _ = try await store.upsertDailyMetrics([chargeDay(day, hrv: 60)], deviceId: canonical + "-noop")
        _ = try await store.upsertMetricSeries([MetricPoint(day: day, key: "hrv_fresh_scoring_valid", value: 0)],
            deviceId: active + "-noop")
        _ = try await store.upsertMetricSeries([MetricPoint(day: day, key: "hrv_fresh_scoring_valid", value: 1)],
            deviceId: canonical + "-noop")
        let filtered = await repo.unionChargeComputedDailyMetrics(store: store, from: day, to: day,
            requiredFreshDay: day)
        XCTAssertNil(filtered.first?.avgHrv, "canonical proof cannot validate or refill the active snapshot")
        XCTAssertEqual(filtered.first?.with(avgHrv: 44, recovery: 72, respRateBpm: 14, avgSdnn: 40), activeRow)
        let display = await repo.unionComputedDailyMetrics(store: store, from: day, to: day)
        XCTAssertEqual(display.first, activeRow)
        _ = try await store.upsertDailyMetrics([chargeDay(day, hrv: nil)], deviceId: active + "-noop")
        let canonicalOwn = await repo.unionChargeComputedDailyMetrics(store: store, from: day, to: day,
            requiredFreshDay: day)
        XCTAssertEqual(canonicalOwn.first?.avgHrv, 60, "an absent active value may use the canonical value's own proof")
    }

    func testUnknownBoundaryHrvDoesNotBlockImportedSeed() async throws {
        let store = try await WhoopStore.inMemory()
        let repo = Repository(deviceId: active)
        repo.setStoreForTesting(store)
        let day = "2026-10-03"
        _ = try await store.upsertDailyMetrics([chargeDay(day, hrv: 44), chargeDay("2026-10-02", hrv: 45)],
            deviceId: active + "-noop")
        let own = await repo.unionChargeComputedDailyMetrics(store: store, from: "2026-10-02", to: day,
            requiredFreshDay: day)
        XCTAssertEqual(own.first?.avgHrv, 45, "unknown older history retains compatibility")
        XCTAssertNil(own.last?.avgHrv)
        let resolved = ChargeBaselines.resolve(imported: [chargeDay(day, hrv: 55)], own: [own.last!],
            anchorDay: day, hrvEpoch: Double(Baselines.isoEpochDay(day)!) * 86_400, recoveryEpoch: 0)
        XCTAssertEqual(resolved.hrvHistory.ownValidNights, 0)
        XCTAssertEqual(resolved.hrvHistory.values, [55])
        XCTAssertTrue(resolved.hrvHistory.seededByImport)
    }

    func testEmptyAndPreservingPassesCannotReviveBoundaryLegacyHrv() async throws {
        try await withPreferences {
            TestCentre.activate(.recovery)
            for preserve in [false, true] {
                for marker: Double? in [nil, 0, 1] {
                    let store = try await WhoopStore.inMemory()
                    try register(DeviceRegistryStore(dbQueue: store.registryWriter), canonicalModel: "4.0")
                    let now = Int(Date().timeIntervalSince1970)
                    let offset = TimeZone.current.secondsFromGMT()
                    let day = AnalyticsEngine.dayString(now, offsetSec: offset)
                    _ = try await store.insert(Streams(rr: [RRInterval(ts: now - 10, rrMs: 1000,
                        srcChannel: .whoop5Historical)]), deviceId: active)
                    _ = try await store.upsertDailyMetrics([chargeDay(day, hrv: 44)], deviceId: canonical + "-noop")
                    if let marker {
                        _ = try await store.upsertMetricSeries([MetricPoint(day: day,
                            key: "hrv_fresh_scoring_valid", value: marker)], deviceId: canonical + "-noop")
                    }
                    let repo = Repository(deviceId: canonical)
                    repo.setStoreForTesting(store)
                    let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: canonical)
                    var trace: [String] = []
                    engine.diagnosticSink = { line, _ in trace.append(line) }
                    await engine.analyzeRecent(maxDays: 2, force: true, preserveUnscoredHistory: preserve)
                    let expected = marker == 1 ? 1 : 0
                    XCTAssertTrue(trace.contains { $0.contains("hrv=own/\(expected)") }, "empty-pass baseline: \(trace)")
                    await repo.refresh()
                    XCTAssertEqual(repo.chargeBaselines?.hrvHistory.ownValidNights, expected)
                    let displayed = try await store.dailyMetrics(deviceId: canonical + "-noop", from: day, to: day)
                    XCTAssertEqual(displayed.first?.avgHrv, 44, "display snapshot must survive")
                }
            }
        }
    }

    func testImportedRewriteCannotInheritRawFreshness() async throws {
        try await withPreferences {
            for preserve in [false, true] {
                let store = try await WhoopStore.inMemory()
                try register(DeviceRegistryStore(dbQueue: store.registryWriter), canonicalModel: "4.0")
                let now = Int(Date().timeIntervalSince1970)
                let offset = TimeZone.current.secondsFromGMT()
                let day = AnalyticsEngine.dayString(now, offsetSec: offset)
                let dayNumber = Baselines.isoEpochDay(day)!
                let source = Repository.wearableImportSources.first!
                let imported = (0..<10).map { back -> DailyMetric in
                    let key = AnalyticsEngine.dayString((dayNumber - back) * 86_400, offsetSec: 0)
                    let row = chargeDay(key, hrv: 50)
                    return row.with(recovery: nil, skinTempDevC: row.skinTempDevC, skinTempC: row.skinTempC)
                }
                _ = try await store.upsertDailyMetrics(imported, deviceId: source)
                _ = try await store.upsertDailyMetrics([chargeDay(day, hrv: 44)], deviceId: canonical + "-noop")
                _ = try await store.upsertMetricSeries([
                    MetricPoint(day: day, key: "hrv_fresh_scoring_valid", value: 1),
                    MetricPoint(day: day, key: "resp_fresh_scoring_valid", value: 1)
                ], deviceId: canonical + "-noop")
                _ = try await store.insert(Streams(rr: [RRInterval(ts: now - 10, rrMs: 1000,
                    srcChannel: .whoop5Historical)]), deviceId: active)
                let repo = Repository(deviceId: canonical)
                repo.setStoreForTesting(store)
                let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: canonical)
                await engine.analyzeRecent(maxDays: 10, force: true, preserveUnscoredHistory: preserve)
                let proof = try await store.chargeHrvProof(deviceId: canonical + "-noop", from: day, to: day)
                XCTAssertEqual(proof, [ChargeHrvProof(day: day, value: 50, freshScoringValid: 0)])
                let display = await repo.unionComputedDailyMetrics(store: store, from: day, to: day)
                XCTAssertEqual(display.first?.avgHrv, 50)
                XCTAssertEqual(display.first?.respRateBpm, 14)
                let reliability = try await repo.signalReliabilityByDay(from: day, to: day)
                XCTAssertEqual(reliability.resp[day]?.value, 14)
                XCTAssertEqual(reliability.resp[day]?.eligible, false)
                XCTAssertFalse(reliability.resp[day]?.matches(14) ?? true)
                let respiratoryProof = try await store.hrvProvenance(deviceIds: [canonical + "-noop"],
                    from: day, to: day)
                XCTAssertEqual(respiratoryProof.first?.respFreshScoringValid, 0)
                XCTAssertNotNil(display.first?.recovery, "the imported recovery remains available for display")
                for boundary: String? in [nil, day] {
                    let own = await repo.unionChargeComputedDailyMetrics(store: store, from: day, to: day,
                        requiredFreshDay: boundary)
                    XCTAssertNil(own.first?.avgHrv, "an imported rewrite cannot inherit the earlier raw proof")
                }
            }
        }
    }

    private func withPreferences(_ body: () async throws -> Void) async throws {
        let defaults = UserDefaults.standard
        let keys = [
            "profile.dateOfBirth", "profile.age", "profile.sex", "profile.weightKg",
            "profile.heightCm", "profile.waistCm", "profile.hrMaxOverride", "profile.stepTicksPerStep",
            "profile.stepsCalibrationCoefficient", "profile.stepsCalibrationSampleDays",
            "profile.stepsCalibrationConfidence", "profile.stepsCalibrationManual",
            "profile.stepsManualCoefficient", "profile.stepsHasBankedMotion",
            "noop.analyzeWatermark", "analyzeRecent.stepsMotionCache.v1",
            "noop.hrvBaselineEpoch", "noop.recoveryBaselineEpoch", UnitPrefs.hrvWindowKey,
            "testcentre.active.recovery", "testcentre.startedAt.recovery",
            RescoreBackgroundScheduler.owedKey, RescoreBackgroundScheduler.owedTokenKey,
            RescoreBackgroundScheduler.lastPassSecondsKey, DayCycleMode.storageKey,
            PuffinExperiment.experimentalSleepV2Key, PuffinExperiment.motionAwareWakeKey,
        ]
        let saved = keys.map { ($0, defaults.object(forKey: $0)) }
        defer {
            for (key, value) in saved {
                if let value { defaults.set(value, forKey: key) }
                else { defaults.removeObject(forKey: key) }
            }
        }
        for key in keys { defaults.removeObject(forKey: key) }
        defaults.set(DayCycleMode.midnight.rawValue, forKey: DayCycleMode.storageKey)
        defaults.set(true, forKey: PuffinExperiment.experimentalSleepV2Key)
        defaults.set(false, forKey: PuffinExperiment.motionAwareWakeKey)
        try await body()
    }

    private func register(_ registry: DeviceRegistryStore, canonicalModel: String) throws {
        try registry.add(PairedDevice(id: canonical, brand: "WHOOP", model: canonicalModel,
            sourceKind: .liveBLE, capabilities: [.hr, .hrv], status: .paired, addedAt: 1, lastSeenAt: 1))
        try registry.add(PairedDevice(id: active, brand: "WHOOP", model: "5.0",
            sourceKind: .liveBLE, capabilities: [.hr, .hrv], status: .active, addedAt: 2, lastSeenAt: 2))
    }

    func testEffectiveHrvEpochUsesValidatedCanonicalBeatsAndLaterManualCut() async throws {
        let store = try await WhoopStore.inMemory()
        let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
        try register(registry, canonicalModel: "WHOOP")
        let repo = Repository(deviceId: active)
        repo.setStoreForTesting(store)
        _ = try await store.insert(Streams(rr: [
            RRInterval(ts: 100, rrMs: 1000),
            RRInterval(ts: 200, rrMs: 1000, srcChannel: .whoop5Realtime),
            RRInterval(ts: 86_500, rrMs: 1000, srcChannel: .whoop5Historical),
        ]), deviceId: canonical)
        _ = try await store.insert(Streams(rr: [
            RRInterval(ts: 172_900, rrMs: 1000, srcChannel: .whoop5Standard),
        ]), deviceId: active)
        let alias = await repo.effectiveHrvEpoch(store: store, activeOwner: active,
            importedAlias: active, manualEpoch: 0, offsetSec: 0)
        XCTAssertEqual(alias, 86_400, "validated canonical history survives physical-ID adoption")
        let manual = await repo.effectiveHrvEpoch(store: store, activeOwner: active,
            importedAlias: canonical, manualEpoch: 259_200, offsetSec: 0)
        XCTAssertEqual(manual, 259_200)
        let shifted = await repo.effectiveHrvEpoch(store: store, activeOwner: active,
            importedAlias: canonical, manualEpoch: 0, offsetSec: -3_600)
        XCTAssertEqual(shifted, 0, "local day, encoded as UTC midnight")
        try registry.add(PairedDevice(id: "four", brand: "WHOOP", model: "4.0",
            sourceKind: .liveBLE, capabilities: [.hr, .hrv], status: .paired, addedAt: 3, lastSeenAt: 3))
        let four = await repo.effectiveHrvEpoch(store: store, activeOwner: "four",
            importedAlias: canonical, manualEpoch: 12_345, offsetSec: 0)
        XCTAssertEqual(four, 12_345, "WHOOP 4 does not inherit WHOOP 5's measurement boundary")
    }

    private func seedBaseline(_ store: WhoopStore, before day: String) async throws {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        let date = try XCTUnwrap(formatter.date(from: day))
        let history = (1...8).map { offset in
            DailyMetric(day: formatter.string(from: date.addingTimeInterval(-Double(offset) * 86_400)),
                totalSleepMin: 480, efficiency: 0.9, deepMin: 90, remMin: 90, lightMin: 300,
                disturbances: 0, restingHr: 60, avgHrv: 32 + Double(offset % 3), recovery: 60,
                strain: nil, exerciseCount: nil)
        }
        _ = try await store.upsertDailyMetrics(history, deviceId: canonical)
    }

    // The same persisted row and store queries the Today explanation consumes.
    private func showsLegacyGap(_ day: DailyMetric, store: WhoopStore, owner: String) async throws -> Bool {
        func dayKey(_ ts: Int?) -> String? {
            ts.map { Repository.localDayKey(Date(timeIntervalSince1970: TimeInterval($0))) }
        }
        let strict = try await store.isWhoop5RRSource(deviceId: owner)
        let firstRecorded = try await store.firstRecordedRRTimestamp(deviceId: owner)
        let firstScorable = try await store.firstScorableWhoop5RRTimestamp(deviceId: owner)
        return day.recovery == nil && Whoop5RR.legacyUnscorableNight(
            strictWhoop5: strict, day: day.day, firstRecordedDay: dayKey(firstRecorded),
            firstScorableDay: dayKey(firstScorable), avgHrv: day.avgHrv, totalSleepMin: day.totalSleepMin)
    }

    func testShortRescoreRetainsOwnWindowAndDropsExpiredImport() async throws {
        try await assertShortRescore(preserve: false, quiet: false)
    }

    func testNormalShortRescoreRemovesQuietRowFromScorerAndDashboard() async throws {
        try await assertShortRescore(preserve: false, quiet: true)
    }

    func testRepairShortRescoreRetainsQuietRowInScorerAndDashboard() async throws {
        try await assertShortRescore(preserve: true, quiet: true)
    }

    private func assertShortRescore(preserve: Bool, quiet: Bool) async throws {
        try await withPreferences {
            let store = try await WhoopStore.inMemory()
            try register(DeviceRegistryStore(dbQueue: store.registryWriter), canonicalModel: "4.0")
            let input = night(daysAgo: quiet ? 2 : 1)
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy-MM-dd"
            let anchor = try XCTUnwrap(formatter.date(from: input.day))
            let own = (2...15).map { offset in
                DailyMetric(day: formatter.string(from: anchor.addingTimeInterval(-Double(offset) * 86_400)),
                    totalSleepMin: 480, efficiency: 0.9, deepMin: 90, remMin: 90, lightMin: 300,
                    disturbances: 0, restingHr: 60, avgHrv: 32, recovery: 60, strain: nil, exerciseCount: nil)
            }
            _ = try await store.upsertDailyMetrics(own, deviceId: canonical + "-noop")
            let expired = DailyMetric(day: formatter.string(from: anchor.addingTimeInterval(-100 * 86_400)),
                totalSleepMin: 480, efficiency: 0.9, deepMin: nil, remMin: nil, lightMin: nil,
                disturbances: nil, restingHr: 50, avgHrv: 90, recovery: 90, strain: nil, exerciseCount: nil)
            _ = try await store.upsertDailyMetrics([expired], deviceId: canonical)
            _ = try await store.insert(Streams(hr: input.hr, rr: input.rr), deviceId: canonical)
            let quietDay = Repository.localDayKey(Date())
            if quiet {
                let midnight = Int(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970)
                _ = try await store.insert(Streams(hr: (0..<100).map {
                    HRSample(ts: midnight + 3_600 + $0, bpm: 60)
                }), deviceId: canonical)
                let row = DailyMetric(day: quietDay, totalSleepMin: 480, efficiency: 0.9,
                    deepMin: nil, remMin: nil, lightMin: nil, disturbances: nil, restingHr: 45,
                    avgHrv: 100, recovery: 80, strain: nil, exerciseCount: nil)
                _ = try await store.upsertDailyMetrics([row], deviceId: canonical + "-noop")
                let samples = try await store.hrSamples(deviceId: canonical,
                    from: midnight - StreamReadCap.lookbackSeconds,
                    to: midnight + StreamReadCap.forwardSeconds, limit: StreamReadCap.hr)
                XCTAssertEqual(samples.count, 100, "quiet fixture must stay below the scorer's lookback HR floor")
            }
            let repo = Repository(deviceId: canonical)
            repo.setStoreForTesting(store)
            let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: canonical)
            var logs: [String] = []
            engine.diagnosticSink = { line, _ in logs.append(line) }
            TestCentre.activate(.recovery)
            await engine.analyzeRecent(maxDays: quiet ? 3 : 2, force: true, preserveUnscoredHistory: preserve)
            await repo.refresh(days: quiet ? 3 : 2)
            let resolved = try XCTUnwrap(repo.chargeBaselines)
            XCTAssertGreaterThanOrEqual(resolved.hrvHistory.ownValidNights, 14)
            XCTAssertFalse(resolved.hrvHistory.seededByImport)
            XCTAssertTrue(logs.contains { $0.contains("hrv=own/\(resolved.hrvHistory.ownValidNights)") },
                          "Short-pass scoring and dashboard must retain the same 21-day own window: \(logs)")
            XCTAssertFalse(resolved.hrvHistory.dayKeys.contains(expired.day))
            if quiet {
                let rows = try await store.dailyMetrics(deviceId: canonical + "-noop", from: quietDay, to: quietDay)
                XCTAssertEqual(rows.first?.avgHrv, preserve ? 100 : nil)
                XCTAssertEqual(resolved.hrvHistory.dayKeys.contains(quietDay), preserve)
            }
        }
    }

    func testLegacySnapshotSurvivesAndClearsAfterSourcePromotion() async throws {
        try await withPreferences {
            let store = try await WhoopStore.inMemory()
            try register(DeviceRegistryStore(dbQueue: store.registryWriter), canonicalModel: "WHOOP")
            let input = night()
            try await seedBaseline(store, before: input.day)
            _ = try await store.insert(Streams(hr: input.hr), deviceId: active)
            let repo = Repository(deviceId: active)
            repo.setStoreForTesting(store)
            let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: canonical)
            func score() async throws -> DailyMetric {
                await engine.analyzeRecent(maxDays: 2, force: true)
                let rows = try await store.dailyMetrics(deviceId: canonical + "-noop", from: input.day, to: input.day)
                return try XCTUnwrap(rows.first)
            }

            let noBeats = try await score()
            XCTAssertGreaterThan(noBeats.totalSleepMin ?? 0, 0)
            XCTAssertNil(noBeats.avgHrv)
            XCTAssertNil(noBeats.recovery)
            let noBeatsGap = try await showsLegacyGap(noBeats, store: store, owner: active)
            XCTAssertFalse(noBeatsGap, "ordinary missing beats must not be blamed on legacy units")

            // Restore the exact 11.5-era computed cells before the 11.6 source-policy re-score. The
            // fixture deliberately gives every unrelated field stale values: only the RR-derived snapshot
            // (HRV + Charge + respiration + SDNN) may survive.
            let legacySnapshot = DailyMetric(day: input.day, totalSleepMin: 1, efficiency: 0.01,
                deepMin: 1, remMin: 1, lightMin: 1, disturbances: 99, restingHr: 199,
                avgHrv: 77.25, recovery: 0.42, strain: 99, exerciseCount: 99,
                respRateBpm: 14.25, avgSdnn: 63.5)
            try await store.persistComputedScores(
                dailyMetrics: [legacySnapshot], metricPoints: [],
                provenance: [ScoreInputProvenanceRow(day: input.day, key: "recovery", sourceId: "legacy-owner")],
                deviceId: canonical + "-noop", from: input.day, to: input.day)
            _ = try await store.insert(Streams(rr: input.rr), deviceId: active)
            let legacy = try await score()
            let legacyFreshness = try await store.metricSeries(deviceId: canonical + "-noop",
                key: "hrv_fresh_scoring_valid", from: input.day, to: input.day)
            XCTAssertEqual(legacyFreshness.first?.value, 0, "legacy restoration must not mark HRV freshly scorable")
            XCTAssertEqual(legacy.avgHrv, legacySnapshot.avgHrv, "the restored HRV cell must survive")
            XCTAssertEqual(legacy.recovery, legacySnapshot.recovery, "HRV and Charge survive as one snapshot")
            XCTAssertEqual(legacy.respRateBpm, legacySnapshot.respRateBpm,
                           "RR-derived respiration must survive with the snapshot")
            XCTAssertEqual(legacy.avgSdnn, legacySnapshot.avgSdnn,
                           "the other persisted RR-only aggregate must survive with the snapshot")
            XCTAssertNotEqual(legacy.totalSleepMin, legacySnapshot.totalSleepMin,
                              "sleep is freshly scored rather than copied from the snapshot")
            XCTAssertNotEqual(legacy.restingHr, legacySnapshot.restingHr,
                              "only the R-R-derived snapshot receives legacy protection")
            let preservedSource = try await store.scoreInputSource(deviceId: canonical + "-noop",
                day: input.day, key: "recovery")
            XCTAssertEqual(preservedSource, "legacy-owner", "snapshot provenance must survive")
            let legacyGap = try await showsLegacyGap(legacy, store: store, owner: active)
            XCTAssertFalse(legacyGap, "a protected snapshot is no longer an empty-score diagnostic")

            let stable = try await score()
            XCTAssertEqual(stable.avgHrv, legacySnapshot.avgHrv)
            XCTAssertEqual(stable.recovery, legacySnapshot.recovery)
            XCTAssertEqual(stable.respRateBpm, legacySnapshot.respRateBpm)
            XCTAssertEqual(stable.avgSdnn, legacySnapshot.avgSdnn)
            let stableSource = try await store.scoreInputSource(deviceId: canonical + "-noop",
                day: input.day, key: "recovery")
            XCTAssertEqual(stableSource, "legacy-owner")

            // Keep the unlabelled-era bounds unchanged: valid displayed HRV alone must rule out this cause.
            let calibrating = DailyMetric(day: legacy.day, totalSleepMin: legacy.totalSleepMin,
                efficiency: legacy.efficiency, deepMin: legacy.deepMin, remMin: legacy.remMin,
                lightMin: legacy.lightMin, disturbances: legacy.disturbances, restingHr: legacy.restingHr,
                avgHrv: 40, recovery: nil, strain: legacy.strain, exerciseCount: legacy.exerciseCount)
            let calibrationGap = try await showsLegacyGap(calibrating, store: store, owner: active)
            XCTAssertFalse(calibrationGap, "valid HRV without Charge is ordinary calibration")

            // A marked but too-thin transport is a current measurement verdict, not a legacy gap. It must
            // clear the old cells rather than letting the protection disguise insufficient new input.
            let thin = try XCTUnwrap(input.rr.first)
            _ = try await store.insert(Streams(rr: [RRInterval(ts: thin.ts, rrMs: thin.rrMs,
                srcChannel: .whoop5Standard)]), deviceId: active)
            let insufficient = try await score()
            XCTAssertNil(insufficient.avgHrv)
            XCTAssertNil(insufficient.recovery)
            XCTAssertNil(insufficient.respRateBpm)
            XCTAssertNil(insufficient.avgSdnn)

            // Recreate the pre-upgrade snapshot, then prove a complete canonical transport supersedes it.
            try await store.persistComputedScores(
                dailyMetrics: [legacySnapshot], metricPoints: [],
                provenance: [ScoreInputProvenanceRow(day: input.day, key: "recovery", sourceId: "legacy-owner")],
                deviceId: canonical + "-noop", from: input.day, to: input.day)
            let tagged = input.rr.map { RRInterval(ts: $0.ts, rrMs: $0.rrMs, srcChannel: .whoop5Historical) }
            let inserted = try await store.insert(Streams(rr: tagged), deviceId: active)
            XCTAssertEqual(inserted.rr, 0, "re-offload changes only source provenance, not row count")
            let restored = try await score()
            let restoredFreshness = try await store.metricSeries(deviceId: canonical + "-noop",
                key: "hrv_fresh_scoring_valid", from: input.day, to: input.day)
            XCTAssertEqual(restoredFreshness.first?.value, 1)
            XCTAssertGreaterThan(try XCTUnwrap(restored.avgHrv), 0)
            XCTAssertNotEqual(restored.avgHrv, legacySnapshot.avgHrv)
            // #2126: the first labelled night restores HRV, but Charge recalibrates against
            // labelled nights rather than scoring against the eight pre-label seed nights.
            XCTAssertNil(restored.recovery)
            await repo.refresh()
            let calibration = RecoveryScorer.calibrationNights(
                nightlyHrv: repo.hrvCalibrationHistory.map(\.value),
                dayKeys: repo.hrvCalibrationHistory.map(\.day), hasRecovery: false)
            XCTAssertEqual(calibration, repo.chargeBaselines?.hrv.nValid,
                           "copy and calibration must use the labelled era even while import seeds")
            XCTAssertNotEqual(restored.respRateBpm, legacySnapshot.respRateBpm)
            XCTAssertNotEqual(restored.avgSdnn, legacySnapshot.avgSdnn)
            let promotedSource = try await store.scoreInputSource(deviceId: canonical + "-noop",
                day: input.day, key: "recovery")
            XCTAssertNil(promotedSource, "a calibrating Charge has no scoring provenance")
            let restoredGap = try await showsLegacyGap(restored, store: store, owner: active)
            XCTAssertFalse(restoredGap)
            let idle = try await score()
            XCTAssertEqual(idle.avgHrv, restored.avgHrv)
            XCTAssertEqual(idle.recovery, restored.recovery)
            let idleGap = try await showsLegacyGap(idle, store: store, owner: active)
            XCTAssertFalse(idleGap, "a cache hit must not revive the explanation")

        }
    }

    // A completed night relative to the test's local day, using the established HR-only sleep fixture.
    private func night(daysAgo: Int = 1) -> (day: String, hr: [HRSample], rr: [RRInterval]) {
        let start = Int(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970) - daysAgo * 86_400
        let day = Repository.localDayKey(Date(timeIntervalSince1970: Double(start)))
        var hr: [HRSample] = []
        var rr: [RRInterval] = []
        for i in 0..<(24 * 3_600) {
            let asleep = i >= 16 * 3_600
            let phase = asleep ? i - 16 * 3_600 : i
            let bpm = asleep ? 64 + Int(sin(Double(phase) / 900) * 5)
                             : 74 + Int(sin(Double(phase) / 500) * 11)
            let ts = start - 16 * 3_600 + i
            hr.append(HRSample(ts: ts, bpm: bpm))
            rr.append(RRInterval(ts: ts, rrMs: 900 + (i.isMultiple(of: 2) ? 16 : -16)))
        }
        return (day, hr, rr)
    }

    func testNightlyScoringRejectsLegacyAliasAndRecomputesAfterZeroInsertPromotion() async throws {
        try await withPreferences {
            let store = try await WhoopStore.inMemory()
            let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
            try register(registry, canonicalModel: "WHOOP")
            let input = night()
            _ = try await store.insert(Streams(hr: input.hr, rr: input.rr), deviceId: canonical)
            let repo = Repository(deviceId: canonical)
            repo.setStoreForTesting(store)
            repo.adoptActiveDeviceId(active)
            let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: canonical)
            var log: [String] = []
            engine.diagnosticSink = { line, _ in log.append(line) }

            await engine.analyzeRecent(maxDays: 2, force: true)
            let before = try await store.dailyMetrics(deviceId: canonical + "-noop",
                from: input.day, to: input.day)
            let first = try XCTUnwrap(before.first)
            XCTAssertGreaterThan(first.totalSleepMin ?? 0, 0, "the fixture must actually score a night")
            XCTAssertNil(first.avgHrv, "legacy alias R-R has unproven units after WHOOP 5 re-pairing")

            let tagged = input.rr.map { RRInterval(ts: $0.ts, rrMs: $0.rrMs, srcChannel: .whoop5Historical) }
            let inserted = try await store.insert(Streams(rr: tagged), deviceId: canonical)
            XCTAssertEqual(inserted.rr, 0, "only provenance changes; the existing interval keys are identical")
            log.removeAll()
            await engine.analyzeRecent(maxDays: 2, force: true)
            let after = try await store.dailyMetrics(deviceId: canonical + "-noop",
                from: input.day, to: input.day)
            let promoted = try XCTUnwrap(after.first?.avgHrv)
            XCTAssertGreaterThan(promoted, 0, "the same engine must replace its cached R-R-less result")

            // Promoted HRV enters the baseline signature before unchanged-cache reuse can be checked.
            log.removeAll()
            await engine.analyzeRecent(maxDays: 2, force: true)
            let stabilized = try await store.dailyMetrics(deviceId: canonical + "-noop",
                from: input.day, to: input.day)
            XCTAssertEqual(stabilized.first?.avgHrv, promoted)

            log.removeAll()
            await engine.analyzeRecent(maxDays: 2, force: true)
            let idle = try await store.dailyMetrics(deviceId: canonical + "-noop",
                from: input.day, to: input.day)
            XCTAssertEqual(idle.first?.avgHrv, promoted)
            XCTAssertTrue(log.contains { $0.contains("dayCache reused=2/2") },
                          "an unchanged pass should reuse both previously scored windows: \(log)")
        }
    }

    func testNightlyScoringKeepsConfirmedWhoop4LegacyIntervalsAfterRePairing() async throws {
        try await withPreferences {
            let store = try await WhoopStore.inMemory()
            let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
            try register(registry, canonicalModel: "4.0")
            let input = night()
            try await seedBaseline(store, before: input.day)
            _ = try await store.insert(Streams(hr: input.hr), deviceId: canonical)
            let repo = Repository(deviceId: active)
            repo.setStoreForTesting(store)
            let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: canonical)
            await engine.analyzeRecent(maxDays: 2, force: true)
            let emptyRows = try await store.dailyMetrics(deviceId: canonical + "-noop", from: input.day, to: input.day)
            let empty = try XCTUnwrap(emptyRows.first)
            XCTAssertNil(empty.avgHrv)
            XCTAssertNil(empty.recovery)
            let emptyGap = try await showsLegacyGap(empty, store: store, owner: canonical)
            XCTAssertFalse(emptyGap)
            _ = try await store.insert(Streams(rr: input.rr), deviceId: canonical)
            await engine.analyzeRecent(maxDays: 2, force: true)
            let rows = try await store.dailyMetrics(deviceId: canonical + "-noop",
                from: input.day, to: input.day)
            let scored = try XCTUnwrap(rows.first)
            XCTAssertGreaterThan(try XCTUnwrap(scored.avgHrv), 0)
            XCTAssertNotNil(scored.recovery)
            let gap = try await showsLegacyGap(scored, store: store, owner: canonical)
            XCTAssertFalse(gap, "WHOOP 4 legacy beats remain supported")
        }
    }

    func testNightlySlidingWindowSelectsOneTransportForTheWholeOlderWindow() async throws {
        try await withPreferences {
            let store = try await WhoopStore.inMemory()
            let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
            try register(registry, canonicalModel: "5.0")
            let input = night()
            let standard = input.rr.map { RRInterval(ts: $0.ts, rrMs: $0.rrMs, srcChannel: .whoop5Standard) }
            _ = try await store.insert(Streams(hr: input.hr, rr: standard), deviceId: canonical)
            // Today's read begins 30h before midnight. Only the older day's extension sees history;
            // that single history record must select history for its ENTIRE window, including overlap.
            let midnight = Int(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970)
            _ = try await store.insert(Streams(rr: [RRInterval(ts: midnight - 31 * 3_600,
                rrMs: 900, srcChannel: .whoop5Historical)]), deviceId: canonical)
            let repo = Repository(deviceId: canonical)
            repo.setStoreForTesting(store)
            let engine = IntelligenceEngine(repo: repo, profile: ProfileStore(), deviceId: canonical)
            await engine.analyzeRecent(maxDays: 2, force: true)
            let rows = try await store.dailyMetrics(deviceId: canonical + "-noop", from: input.day, to: input.day)
            let scored = try XCTUnwrap(rows.first)
            XCTAssertGreaterThan(scored.totalSleepMin ?? 0, 0)
            XCTAssertNotNil(scored.restingHr)
            XCTAssertNil(scored.avgHrv, "the history-only window has no beats inside the sleep; cached standard beats must not leak in")
        }
    }

    func testManualNapAndSelfHealGuardAliasBeforeRepositoryAdoptsActiveDevice() async throws {
        try await withPreferences {
            let store = try await WhoopStore.inMemory()
            let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
            try register(registry, canonicalModel: "WHOOP")
            let start = 1_700_000_000
            let duration = 6 * 3_600
            let hr = (0..<duration).map { HRSample(ts: start + $0, bpm: 52 + ($0 / 60) % 3) }
            let grav = (0..<duration).map { GravitySample(ts: start + $0, x: 0, y: 0, z: 1) }
            _ = try await store.insert(Streams(hr: hr, gravity: grav), deviceId: canonical)
            // AppModel adopts the new identity asynchronously; the repository still holds my-whoop.
            let repo = Repository(deviceId: canonical)
            repo.setStoreForTesting(store)
            await repo.addManualNap(startTs: start, endTs: start + duration)
            let before = try await store.sleepSessions(deviceId: canonical + "-noop",
                from: start, to: start + duration, limit: 10)
            let baseline = try XCTUnwrap(before.first?.stagesJSON)
            let rr = (0..<duration).map { i in
                RRInterval(ts: start + i, rrMs: 1000 + Int(40 * sin(2 * Double.pi * Double(i) / 4)))
            }
            _ = try await store.insert(Streams(rr: rr), deviceId: canonical)
            let guarded = await repo.selfHealEditedStages(from: start, to: start + duration)
            XCTAssertEqual(guarded.first?.stagesJSON, baseline,
                           "self-heal must not use unlabelled alias R-R even before active-ID adoption")
            try register(registry, canonicalModel: "4.0")
            let confirmedFour = await repo.selfHealEditedStages(from: start, to: start + duration)
            XCTAssertNotEqual(confirmedFour.first?.stagesJSON, baseline,
                              "the fixture must expose R-R-dependent staging, retained for confirmed WHOOP 4")
        }
    }
}
