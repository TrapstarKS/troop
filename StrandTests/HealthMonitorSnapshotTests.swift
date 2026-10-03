import XCTest
import StrandAnalytics
import WhoopStore
@testable import Strand

final class HealthMonitorSnapshotTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-06-20T12:00:00Z")!

    private func metric(day: String, totalSleepMin: Double? = nil, restingHr: Int? = nil,
                        avgHrv: Double? = nil, spo2Pct: Double? = nil, skinTempDevC: Double? = nil,
                        respRateBpm: Double? = nil, spo2Red: Int? = nil, spo2Ir: Int? = nil) -> DailyMetric {
        DailyMetric(day: day, totalSleepMin: totalSleepMin, efficiency: nil, deepMin: nil,
                    remMin: nil, lightMin: nil, disturbances: nil, restingHr: restingHr,
                    avgHrv: avgHrv, recovery: nil, strain: nil, exerciseCount: nil,
                    spo2Pct: spo2Pct, skinTempDevC: skinTempDevC, respRateBpm: respRateBpm,
                    spo2Red: spo2Red, spo2Ir: spo2Ir)
    }

    func testBankedLocalNightBeforeRolloverMatchesHomeAndBecomesCurrent() {
        let early = Calendar.current.date(from: DateComponents(year: 2026, month: 6, day: 20, hour: 2))!
        let values = [SourcedDailyMetric(metric: metric(day: "2026-06-19", avgHrv: 80), source: .whoopImport),
                      SourcedDailyMetric(metric: metric(day: "2026-06-20", totalSleepMin: 420, avgHrv: 70), source: .whoopImport)]
        XCTAssertEqual(HealthMonitorSnapshot.dayKey(days: values.map(\.metric), now: early), "2026-06-20")
        let result = HealthMonitorSnapshot.rows(sourceRows: values, now: early, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        let hrv = result.first { $0.id == "hrv" }!
        XCTAssertTrue(hrv.isCurrent)
        XCTAssertEqual(hrv.reading.value, 70)
    }

    func testUnbankedLocalDayKeepsLogicalDayAndRolloverDoesNotPromoteOldVital() {
        let calendar = Calendar.current
        let early = calendar.date(from: DateComponents(year: 2026, month: 6, day: 20, hour: 2))!
        let rolled = calendar.date(from: DateComponents(year: 2026, month: 6, day: 20, hour: 4, minute: 1))!
        let values = [SourcedDailyMetric(metric: metric(day: "2026-06-19", avgHrv: 80), source: .whoopImport),
                      SourcedDailyMetric(metric: metric(day: "2026-06-20"), source: .whoopImport)]
        XCTAssertEqual(HealthMonitorSnapshot.dayKey(days: values.map(\.metric), now: early), "2026-06-19")
        XCTAssertEqual(HealthMonitorSnapshot.dayKey(days: values.map(\.metric), now: rolled), "2026-06-20")
        let result = HealthMonitorSnapshot.rows(sourceRows: values, now: rolled, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertFalse(result.first { $0.id == "hrv" }!.isCurrent)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .unavailable)
    }

    private func rows(currentHrv: Double? = 80, source: DailyMetricSource = .whoopImport) -> [SourcedDailyMetric] {
        (1...20).map { index in
            SourcedDailyMetric(metric: metric(day: String(format: "2026-06-%02d", index),
                                                   restingHr: 55, avgHrv: index == 20 ? currentHrv : 80,
                                                   spo2Pct: 97, skinTempDevC: 0.1, respRateBpm: 14.5),
                               source: source)
        }
    }

    func testFiveCurrentReadingsUsePersonalRanges() {
        let result = HealthMonitorSnapshot.rows(sourceRows: rows(), now: now, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.map(\.id), ["hrv", "rhr", "resp", "spo2", "skin"])
        XCTAssertTrue(result.allSatisfy { $0.assessment.status == .withinRange })
        XCTAssertTrue(result.allSatisfy { $0.assessment.lower != nil && $0.assessment.upper != nil })
    }

    func testMissingTodayIsNeverCountedWithinRange() {
        let result = HealthMonitorSnapshot.rows(sourceRows: rows(currentHrv: nil), now: now, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        let hrv = result.first { $0.id == "hrv" }!
        XCTAssertFalse(hrv.isCurrent)
        XCTAssertEqual(hrv.reading.day, "2026-06-19")
        XCTAssertEqual(hrv.assessment.status, .unavailable)
    }

    func testUnverifiedCurrentComputedHrvCannotClaimWithinRange() {
        let values = rows(source: .noopComputed)
        var evidence = Dictionary(uniqueKeysWithValues: values.map {
            ($0.metric.day, HealthSignalReliability.Record(value: 80, eligible: true))
        })
        evidence["2026-06-20"] = .init(value: 80, eligible: false)
        let result = HealthMonitorSnapshot.rows(sourceRows: values, now: now, hrvReliabilityByDay: evidence,
                                                hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .unverified)
    }

    func testUnverifiedComputedHistoryCannotBuildTrustedBaseline() {
        let values = rows(source: .noopComputed)
        var evidence = Dictionary(uniqueKeysWithValues: values.map {
            ($0.metric.day, HealthSignalReliability.Record(value: 80, eligible: false))
        })
        evidence["2026-06-20"] = .init(value: 80, eligible: true)
        let result = HealthMonitorSnapshot.rows(sourceRows: values, now: now, hrvReliabilityByDay: evidence,
                                                hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .calibrating)
    }

    func testComputedHrvWithoutProvenanceCannotClaimWithinRange() {
        let result = HealthMonitorSnapshot.rows(sourceRows: rows(source: .noopComputed), now: now,
                                                hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .unverified)
    }

    func testComputedHrvCannotBorrowEligibilityForAnotherValue() {
        let evidence = ["2026-06-20": HealthSignalReliability.Record(value: 81, eligible: true)]
        let result = HealthMonitorSnapshot.rows(sourceRows: rows(source: .noopComputed), now: now,
                                                hrvReliabilityByDay: evidence, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .unverified)
    }

    func testImportedHrvIsNotInvalidatedByComputedProvenance() {
        let evidence = ["2026-06-20": HealthSignalReliability.Record(value: 80, eligible: false)]
        let result = HealthMonitorSnapshot.rows(sourceRows: rows(), now: now, hrvReliabilityByDay: evidence,
                                                hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .withinRange)
    }

    func testFreshRespirationRemainsTrustedWhenHrvIsRejected() {
        let values = rows(source: .noopComputed)
        let respiration = Dictionary(uniqueKeysWithValues: values.map {
            ($0.metric.day, HealthSignalReliability.Record(value: 14.5, eligible: true))
        })
        let hrv = Dictionary(uniqueKeysWithValues: values.map {
            ($0.metric.day, HealthSignalReliability.Record(value: 80, eligible: false))
        })
        let result = HealthMonitorSnapshot.rows(sourceRows: values, now: now,
                                                hrvReliabilityByDay: hrv, respReliabilityByDay: respiration,
                                                hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "hrv" }!.assessment.status, .unverified)
        XCTAssertEqual(result.first { $0.id == "resp" }!.assessment.status, .withinRange)
    }

    func testRetainedRespirationCannotClaimNormalOrEnterReports() {
        let values = rows(source: .noopComputed)
        for evidence: [String: HealthSignalReliability.Record]? in [nil, ["2026-06-20": .init(value: 14.5, eligible: false)],
                                                                   ["2026-06-20": .init(value: 15, eligible: true)]] {
            let result = HealthMonitorSnapshot.rows(sourceRows: values, now: now,
                                                    respReliabilityByDay: evidence,
                                                    hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
            XCTAssertEqual(result.first { $0.id == "resp" }!.assessment.status, .unverified)
            XCTAssertTrue(HealthMonitorSnapshot.resolvedValues(key: "resp", sourceRows: values,
                                                               absoluteSkin: false, respReliabilityByDay: evidence).isEmpty)
        }
    }

    func testImportedRespirationDoesNotNeedComputedFreshnessMarkers() {
        let result = HealthMonitorSnapshot.rows(sourceRows: rows(), now: now,
                                                respReliabilityByDay: ["2026-06-20": .init(value: 14.5, eligible: false)],
                                                hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "resp" }!.assessment.status, .withinRange)
    }

    func testUnverifiedWinnerDoesNotFallThroughToAnotherSourceInReports() {
        let day = "2026-06-20"
        let values = [SourcedDailyMetric(metric: metric(day: day, avgHrv: 500), source: .whoopImport),
                      SourcedDailyMetric(metric: metric(day: day, avgHrv: 80), source: .noopComputed)]
        let evidence = [day: HealthSignalReliability.Record(value: 80, eligible: true)]
        XCTAssertTrue(HealthMonitorSnapshot.resolvedValues(key: "hrv", sourceRows: values, absoluteSkin: false,
                                                           hrvReliabilityByDay: evidence).isEmpty)
    }

    func testNonfiniteAndImplausibleHrvCannotBeFormattedAsAReading() {
        for value in [Double.nan, Double.infinity, 1e300] {
            let result = HealthMonitorSnapshot.rows(sourceRows: rows(currentHrv: value), now: now,
                                                    hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
            let hrv = result.first { $0.id == "hrv" }!
            XCTAssertNil(hrv.formattedValue)
            XCTAssertNotEqual(hrv.assessment.status, .withinRange)
        }
    }

    func testAbsentOxygenStaysUnavailable() {
        let values = rows().map { source in
            SourcedDailyMetric(metric: metric(day: source.metric.day, avgHrv: 80, spo2Red: 100, spo2Ir: 110),
                               source: .noopComputed)
        }
        let result = HealthMonitorSnapshot.rows(sourceRows: values, now: now, hrvBaselineEpoch: 0, recoveryBaselineEpoch: 0)
        XCTAssertEqual(result.first { $0.id == "spo2" }!.assessment.status, .unavailable)
    }
}
